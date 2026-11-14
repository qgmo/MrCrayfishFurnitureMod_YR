package com.mrcrayfish.furniture.client.tv;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * 一路解码（悠然服务器魔改）：打开本机转发的地址，画面转 RGBA（缩到不超过 {@value #MAX_WIDTH}×{@value #MAX_HEIGHT}），
 * 声音转 48 kHz、16 位、双声道（世界里播放时再并成单声道）。
 * <p>
 * FFmpeg 只开 http、tcp 两种协议（{@code protocol_whitelist}），只认 MP4 的解复用器，解码器只开 H.264 / H.265 / AV1 与 AAC / MP3。
 */
final class VideoDecoder implements AutoCloseable
{
    static final int MAX_WIDTH = 854;
    static final int MAX_HEIGHT = 480;
    static final int SAMPLE_RATE = 48000;

    private final FFmpegFrameGrabber grabber;
    private final boolean video;
    private final boolean audio;

    VideoDecoder(String url, boolean video, boolean audio, long startMs) throws Exception
    {
        this.video = video;
        this.audio = audio;
        grabber = new FFmpegFrameGrabber(url);
        grabber.setFormat("mp4");
        grabber.setOption("protocol_whitelist", "http,tcp");
        grabber.setOption("format_whitelist", "mov,mp4,m4a,3gp,3g2,mj2");
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
            if(w > MAX_WIDTH || h > MAX_HEIGHT)
            {
                double scale = Math.min(MAX_WIDTH / (double) w, MAX_HEIGHT / (double) h);
                grabber.setImageWidth(Math.max(2, (int) (w * scale) & ~1));
                grabber.setImageHeight(Math.max(2, (int) (h * scale) & ~1));
            }
        }
        if(startMs > 0)
            seek(startMs);
    }

    boolean hasVideo()
    {
        return video && grabber.hasVideo();
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
