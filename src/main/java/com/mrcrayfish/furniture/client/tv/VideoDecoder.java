package com.mrcrayfish.furniture.client.tv;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * 一路解码（悠然服务器魔改）：打开本机转发的地址，画面转 RGBA（缩到不超过清晰度上限对应的尺寸，见 {@link #maxSize(int)}），
 * 声音转 48 kHz、16 位、双声道（世界里播放时再并成单声道）。
 * <p>
 * FFmpeg 只开 http、tcp 两种协议（{@code protocol_whitelist}），点播只认 MP4 的解复用器，直播（服主 11-18）只认 FLV 或 MPEG-TS
 * （HLS 的分片由 {@link HlsFeed} 在 Java 里接起来，不开 FFmpeg 的 HLS 解复用器），解码器只开 H.264 / H.265 / AV1 与 AAC / MP3。
 */
final class VideoDecoder implements AutoCloseable
{
    static final int SAMPLE_RATE = 48000;

    /** 封装格式：点播的 MP4，直播的 FLV、MPEG-TS */
    enum Container
    {
        MP4("mp4", "mov,mp4,m4a,3gp,3g2,mj2"),
        FLV("flv", "flv"),
        MPEGTS("mpegts", "mpegts");

        final String format;
        final String whitelist;

        Container(String format, String whitelist)
        {
            this.format = format;
            this.whitelist = whitelist;
        }

        boolean live()
        {
            return this != MP4;
        }
    }

    private final FFmpegFrameGrabber grabber;
    /** 换清晰度后旧的单文件 MP4 只解声音（服主 11-18 无缝切换）：画面可以中途关掉 */
    private volatile boolean video;
    private final boolean audio;

    /**
     * 清晰度上限（bilibili 的 qn）对应的画面尺寸上限 {宽, 高}：360P 640×360、480P 854×480、720P 1280×720、1080P 1920×1080。
     * 不是四档之一的按不超过它的最高一档算，比 360P 还低的按 360P。
     */
    static int[] maxSize(int qn)
    {
        if(qn >= BiliData.QN_1080P)
            return new int[]{1920, 1080};
        if(qn >= BiliData.QN_720P)
            return new int[]{1280, 720};
        if(qn >= BiliData.QN_480P)
            return new int[]{854, 480};
        return new int[]{640, 360};
    }

    /** 源画面 w×h 缩进 maxW×maxH：保持比例、宽高取偶数、不放大；不需要缩时返回 null */
    @Nullable
    static int[] fit(int w, int h, int maxW, int maxH)
    {
        if(w <= 0 || h <= 0 || (w <= maxW && h <= maxH))
            return null;
        double scale = Math.min(maxW / (double) w, maxH / (double) h);
        return new int[]{Math.max(2, (int) (w * scale) & ~1), Math.max(2, (int) (h * scale) & ~1)};
    }

    /** 一帧 RGBA 的字节数上限（按清晰度上限算，渲染和队列内存都以此为准） */
    static int maxFrameBytes(int qn)
    {
        int[] m = maxSize(qn);
        return m[0] * m[1] * 4;
    }

    VideoDecoder(String url, boolean video, boolean audio, long startMs, int maxQn) throws Exception
    {
        this(url, video, audio, startMs, maxQn, Container.MP4);
    }

    VideoDecoder(String url, boolean video, boolean audio, long startMs, int maxQn, Container container) throws Exception
    {
        this.video = video;
        this.audio = audio;
        grabber = new FFmpegFrameGrabber(url);
        grabber.setFormat(container.format);
        grabber.setOption("protocol_whitelist", "http,tcp");
        grabber.setOption("format_whitelist", container.whitelist);
        if(container.live())
        {
            // 直播：少探测一会儿，早点出画面
            grabber.setOption("analyzeduration", "2000000");
            grabber.setOption("probesize", "2000000");
        }
        // 网络读超时（微秒），本机转发那头断了也不会一直卡住
        grabber.setOption("rw_timeout", "20000000");
        grabber.setOption("reconnect", "0");
        if(video)
        {
            grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
            grabber.setVideoOption("threads", "2");
            grabber.setVideoOption("codec_whitelist", "h264,hevc,av1,libdav1d,libaom-av1");
        }
        if(audio)
        {
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            grabber.setAudioChannels(2);
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.setAudioOption("codec_whitelist", "aac,aac_fixed,mp3,mp3float");
        }
        grabber.start();
        if(video)
        {
            int w = grabber.getImageWidth(), h = grabber.getImageHeight();
            int[] max = maxSize(maxQn);
            int[] fit = fit(w, h, max[0], max[1]);
            if(fit != null)
            {
                grabber.setImageWidth(fit[0]);
                grabber.setImageHeight(fit[1]);
            }
        }
        if(startMs > 0)
            seek(startMs);
    }

    boolean hasVideo()
    {
        return video && grabber.hasVideo();
    }

    boolean audioEnabled()
    {
        return audio;
    }

    /** 以后只解声音（不再解画面，省下解码） */
    void setVideoEnabled(boolean enabled)
    {
        video = enabled && video;
    }

    boolean hasAudio()
    {
        return audio && grabber.hasAudio();
    }

    long lengthMs()
    {
        return Math.max(0, grabber.getLengthInTime() / 1000);
    }

    void seek(long ms) throws Exception
    {
        // 接口给的时长可能比流本身长：跳到流的末尾之后什么也解不出来（画面成噪点），改成从末尾前一点解，停在最后一帧
        long length = lengthMs();
        if(length > 0 && ms > length - 500)
            ms = Math.max(0, length - 1500);
        if(video)
            grabber.setTimestamp(ms * 1000L);
        else
            grabber.setAudioTimestamp(ms * 1000L);
    }

    /** 下一帧（画面或声音）；读完返回 null */
    @Nullable
    Frame next() throws Exception
    {
        return grabber.grabFrame(audio, video, true, false, false);
    }

    static boolean isImage(Frame f)
    {
        return f.image != null && f.image.length > 0 && f.image[0] != null;
    }

    static boolean isAudio(Frame f)
    {
        return f.samples != null && f.samples.length > 0 && f.samples[0] != null;
    }

    /** 画面按行拷进紧凑的 RGBA 缓冲（RGBA 的行跨度可能大于宽 × 4） */
    static void copyImage(Frame f, ByteBuffer dst)
    {
        ByteBuffer src = (ByteBuffer) f.image[0];
        int rowBytes = f.imageWidth * 4;
        dst.clear();
        int stride = f.imageStride;
        if(stride == rowBytes)
        {
            src.limit(src.capacity()).position(0);
            src.limit(Math.min(src.capacity(), rowBytes * f.imageHeight));
            dst.put(src);
        }
        else
        {
            for(int y = 0; y < f.imageHeight; y++)
            {
                src.limit(src.capacity());
                src.position(y * stride);
                src.limit(y * stride + rowBytes);
                dst.put(src);
            }
        }
        src.limit(src.capacity()).position(0);
        dst.flip();
    }

    /** 声音帧拷成 short 数组（交错的双声道） */
    static short[] copySamples(Frame f)
    {
        ShortBuffer src = (ShortBuffer) f.samples[0];
        src.rewind();
        short[] out = new short[src.remaining()];
        src.get(out);
        src.rewind();
        return out;
    }

    static int channels(Frame f)
    {
        return Math.max(1, f.audioChannels);
    }

    @Override
    public void close()
    {
        try
        {
            grabber.stop();
            grabber.release();
        }
        catch(Exception e)
        {
            // 关的时候出错不要紧
        }
    }
}
