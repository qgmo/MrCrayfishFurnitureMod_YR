package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.tv.TvChannel;
import com.mrcrayfish.furniture.tv.TvClock;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.BlockPos;
import org.bytedeco.javacv.Frame;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一台电视正在播的视频（悠然服务器魔改，只在客户端用）。
 * <p>
 * 后台线程：取视频信息与流地址 → 登记本机转发 → 解码（MP4 一路、DASH 画面声音各一路），画面、声音放进有上限的队列。
 * 渲染线程（{@link #update}）：按服务端给的目标位置取该显示的那一帧，{@code glTexSubImage2D} 更新纹理；声音交给 {@link AudioOut}。
 * 本地位置与目标差太多时跳转（{@link TvClock#decide}），跳转最多每 3 秒一次。解码出错不会带崩游戏：出错就停在提示上，过一会儿重开。
 */
public final class VideoSession
{
    public enum Status
    {
        /** 取视频信息、流地址、打开解码器 */
        LOADING,
        /** 在播（可能在缓冲） */
        PLAYING,
        /** 播完了（回到噪点） */
        ENDED,
        /** 出错，见 {@link #getErrorKey()} */
        ERROR
    }

    private static final int VIDEO_QUEUE = 12;
    private static final int AUDIO_QUEUE = 160;
    private static final long SEEK_COOLDOWN_MS = 3_000L;
    private static final AtomicInteger THREADS = new AtomicInteger();

    /** 解码出来的一帧画面 */
    static final class VideoFrame
    {
        final ByteBuffer data;
        int width;
        int height;
        long ptsMs;
        int generation;

        VideoFrame(int capacity)
        {
            data = ByteBuffer.allocateDirect(capacity);
        }
    }

    /** 一段声音（交错的 16 位样本） */
    static final class AudioChunk
    {
        final short[] samples;
        final int channels;
        final long ptsMs;
        final int generation;

        AudioChunk(short[] samples, int channels, long ptsMs, int generation)
        {
            this.samples = samples;
            this.channels = channels;
            this.ptsMs = ptsMs;
            this.generation = generation;
        }

        long durationMs()
        {
            return samples.length / channels * 1000L / VideoDecoder.SAMPLE_RATE;
        }
    }

    private final BlockPos pos;
    private final TvChannel channel;
    private final int maxQuality;

    private volatile Status status = Status.LOADING;
    private volatile String errorKey = "";
    private volatile boolean closed;
    private volatile long durationMs;
    @Nullable
    private volatile BiliData.VideoInfo info;
    @Nullable
    private volatile List<Danmaku.Comment> danmaku;
    private volatile boolean danmakuRequested;

    /** 渲染线程写、解码线程读：目标位置、是否在播 */
    private volatile long targetMs;
    /** 跳转请求：每次跳转 generation 加一，旧的帧丢掉 */
    private volatile long seekToMs = -1;
    private volatile int generation;

    final BlockingQueue<VideoFrame> videoQueue = new ArrayBlockingQueue<>(VIDEO_QUEUE);
    private final BlockingQueue<VideoFrame> freeFrames = new ArrayBlockingQueue<>(VIDEO_QUEUE + 4);
    private final AtomicInteger allocated = new AtomicInteger();
    final BlockingQueue<AudioChunk> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE);
    private volatile boolean videoEof;
    private volatile boolean hasAudio;

    private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
    private final List<String> relayUrls = Collections.synchronizedList(new ArrayList<>());
    private volatile BiliData.Stream stream;
    private volatile long streamAt;

    // ---- 只在渲染线程用 ----
    private int texture = -1;
    private int texWidth;
    private int texHeight;
    private long shownPts = -1;
    /** 跳转后第一帧之前为 -1（对齐判断用） */
    private long syncPts = -1;
    private long lastSeekAt;
    private long lastStateAt;
    private final AudioOut audio = new AudioOut();
    private int seenGeneration;
    private final java.util.Map<Integer, List<Danmaku.Placed>> layouts = new java.util.HashMap<>();
    private List<Danmaku.Comment> layoutSource;

    public VideoSession(BlockPos pos, TvChannel channel, int maxQuality, long startTargetMs)
    {
        this.pos = pos;
        this.channel = channel;
        this.maxQuality = maxQuality;
        this.targetMs = startTargetMs;
        Thread t = new Thread(this::run, "cfm-tv-decode-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        threads.add(t);
        t.start();
    }

    public BlockPos getPos()
    {
        return pos;
    }

    public TvChannel getChannel()
    {
        return channel;
    }

    public Status getStatus()
    {
        return status;
    }

    public String getErrorKey()
    {
        return errorKey;
    }

    public long getDurationMs()
    {
        return durationMs;
    }

    @Nullable
    public BiliData.VideoInfo getInfo()
    {
        return info;
    }

    /** 有没有画面可画 */
    public boolean hasPicture()
    {
        return texture >= 0 && shownPts >= 0;
    }

    public int getTexture()
    {
        return texture;
    }

    public int getTexWidth()
    {
        return texWidth;
    }

    public int getTexHeight()
    {
        return texHeight;
    }

    /** 弹幕排版（渲染线程，按画布宽缓存） */
    public List<Danmaku.Placed> danmakuLayout(int canvasWidth, java.util.function.Supplier<List<Danmaku.Placed>> compute)
    {
        if(layoutSource != danmaku || layouts.size() > 4)
        {
            layouts.clear();
            layoutSource = danmaku;
        }
        return layouts.computeIfAbsent(canvasWidth, w -> compute.get());
    }

    /** 弹幕（还没取到时为 null，第一次要时在后台取） */
    @Nullable
    public List<Danmaku.Comment> danmaku()
    {
        if(!danmakuRequested && info != null)
        {
            danmakuRequested = true;
            long cid = info.cid;
            Thread t = new Thread(() -> danmaku = BiliApi.danmaku(cid), "cfm-tv-danmaku");
            t.setDaemon(true);
            t.start();
        }
        return danmaku;
    }

    // ================= 后台线程 =================

    private void run()
    {
        int failures = 0;
        while(!closed)
        {
            try
            {
                if(info == null)
                {
                    BiliData.VideoInfo i = BiliApi.view(channel);
                    info = i;
                    durationMs = i.durationMs;
                }
                if(stream == null)
                {
                    refreshStream();
                }
                if(durationMs > 0 && targetMs >= durationMs)
                {
                    status = Status.ENDED;
                    sleep(250);
                    continue;
                }
                playOnce();
                failures = 0;
            }
            catch(BiliData.BiliException e)
            {
                fail(e.getKey(), e.getMessage());
                // 接口出错：30 秒后再试（不高频重试）
                sleepUntilClosed(30_000);
                stream = null;
                if(!"cfm.tv.bili.network".equals(e.getKey()) && !"cfm.tv.bili.blocked".equals(e.getKey()))
                    info = null;
            }
            catch(InterruptedException e)
            {
                break;
            }
            catch(Throwable t)
            {
                failures++;
                MrCrayfishFurnitureMod.logger().warn("TV video at {}: decoder error: {}", pos, t.toString());
                fail("cfm.tv.video.decode_error", t.toString());
                sleepUntilClosed(failures >= 3 ? 30_000 : 3_000);
            }
        }
        releaseRelay();
    }

    private void fail(String key, String detail)
    {
        if(closed)
            return;
        errorKey = key;
        status = Status.ERROR;
        MrCrayfishFurnitureMod.logger().info("TV video at {}: {} ({})", pos, key, detail);
    }

    private synchronized void refreshStream() throws BiliData.BiliException
    {
        BiliData.VideoInfo i = info;
        if(i == null)
            return;
        // 短时间内多路同时要刷新，只刷一次
        if(stream != null && System.currentTimeMillis() - streamAt < 10_000L)
            return;
        BiliData.Stream s = BiliApi.playurl(i, maxQuality);
        stream = s;
        streamAt = System.currentTimeMillis();
        if(s.durationMs > 0 && durationMs <= 0)
            durationMs = s.durationMs;
    }

    private LocalRelay.Source source(boolean audioTrack)
    {
        return new LocalRelay.Source()
        {
            @Override
            public List<URI> urls()
            {
                BiliData.Stream s = stream;
                if(s == null)
                    return Collections.emptyList();
                return audioTrack ? s.audio : s.video;
            }

            @Override
            public void refresh() throws IOException
            {
                try
                {
                    refreshStream();
                }
                catch(BiliData.BiliException e)
                {
                    throw new IOException(e.getMessage());
                }
            }
        };
    }

    /** 打开解码器从目标位置开始解，直到读完、出错或关掉 */
    private void playOnce() throws Exception
    {
        BiliData.Stream s = stream;
        if(s == null)
            return;
        releaseRelay();
        String videoUrl = LocalRelay.register(source(false));
        relayUrls.add(videoUrl);
        String audioUrl = null;
        if(s.dash && !s.audio.isEmpty())
        {
            audioUrl = LocalRelay.register(source(true));
            relayUrls.add(audioUrl);
        }
        int gen = generation;
        long start = seekToMs >= 0 ? seekToMs : targetMs;
        seekToMs = -1;
        videoEof = false;
        try(VideoDecoder video = new VideoDecoder(videoUrl, true, !s.dash, start))
        {
            if(durationMs <= 0)
                durationMs = video.lengthMs();
            hasAudio = video.hasAudio();
            Thread audioThread = null;
            if(audioUrl != null)
            {
                hasAudio = true;
                String url = audioUrl;
                audioThread = new Thread(() -> audioLoop(url, start, gen), "cfm-tv-audio-" + THREADS.incrementAndGet());
                audioThread.setDaemon(true);
                audioThread.setPriority(Thread.NORM_PRIORITY - 1);
                threads.add(audioThread);
                audioThread.start();
            }
            status = Status.PLAYING;
            decodeLoop(video, gen, true);
            if(audioThread != null)
            {
                audioThread.interrupt();
                audioThread.join(2000);
                threads.remove(audioThread);
            }
        }
    }

    /** DASH 的声音单独一路；出错（例如地址过期、网络断了）时隔一会儿从当前位置重开，连续失败 5 次才放弃 */
    private void audioLoop(String url, long start, int startGen)
    {
        int failures = 0;
        long from = start;
        int gen = startGen;
        while(!closed && !Thread.currentThread().isInterrupted())
        {
            try(VideoDecoder decoder = new VideoDecoder(url, false, true, from))
            {
                failures = 0;
                decodeLoop(decoder, gen, false);
                return;
            }
            catch(InterruptedException e)
            {
                return;
            }
            catch(Throwable t)
            {
                if(++failures >= 5)
                {
                    MrCrayfishFurnitureMod.logger().info("TV video at {}: audio stopped: {}", pos, t.toString());
                    return;
                }
                try
                {
                    Thread.sleep(2000L * failures);
                }
                catch(InterruptedException e)
                {
                    return;
                }
                gen = generation;
                from = Math.max(0, seekToMs >= 0 ? seekToMs : targetMs);
            }
        }
    }

    private void decodeLoop(VideoDecoder decoder, int startGen, boolean main) throws Exception
    {
        int gen = startGen;
        boolean eof = false;
        while(!closed && !Thread.currentThread().isInterrupted())
        {
            int g = generation;
            if(g != gen)
            {
                long to = Math.max(0, seekToMs >= 0 ? seekToMs : targetMs);
                decoder.seek(to);
                gen = g;
                eof = false;
                if(main)
                    videoEof = false;
            }
            if(eof)
            {
                if(main)
                    videoEof = true;
                Thread.sleep(50);
                continue;
            }
            Frame f = decoder.next();
            if(f == null)
            {
                eof = true;
                continue;
            }
            long pts = f.timestamp / 1000L;
            if(VideoDecoder.isImage(f) && f.imageWidth > 0 && f.imageHeight > 0)
            {
                VideoFrame vf = takeFree(f.imageWidth * f.imageHeight * 4);
                if(vf == null)
                    return;
                VideoDecoder.copyImage(f, vf.data);
                vf.width = f.imageWidth;
                vf.height = f.imageHeight;
                vf.ptsMs = pts;
                vf.generation = gen;
                while(!closed && generation == gen && !videoQueue.offer(vf, 50, TimeUnit.MILLISECONDS))
                {
                    // 队列满（暂停、或解得比播得快）：等
                }
                if(generation != gen)
                    recycle(vf);
            }
            else if(VideoDecoder.isAudio(f))
            {
                AudioChunk chunk = new AudioChunk(VideoDecoder.copySamples(f), VideoDecoder.channels(f), pts, gen);
                while(!closed && generation == gen && !audioQueue.offer(chunk, 50, TimeUnit.MILLISECONDS))
                {
                    // 等
                }
            }
        }
    }

    @Nullable
    private VideoFrame takeFree(int capacity) throws InterruptedException
    {
        while(!closed)
        {
            VideoFrame f = freeFrames.poll();
            if(f == null && allocated.get() < VIDEO_QUEUE + 2)
            {
                allocated.incrementAndGet();
                f = new VideoFrame(capacity);
            }
            if(f == null)
            {
                // 都在队列里或正在显示：等渲染线程还回来
                f = freeFrames.poll(50, TimeUnit.MILLISECONDS);
                if(f == null)
                    continue;
            }
            if(f.data.capacity() < capacity)
                f = new VideoFrame(capacity);
            return f;
        }
        return null;
    }

    private void recycle(VideoFrame f)
    {
        freeFrames.offer(f);
    }

    private void releaseRelay()
    {
        synchronized(relayUrls)
        {
            for(String u : relayUrls)
                LocalRelay.unregister(u);
            relayUrls.clear();
        }
    }

    private void sleep(long ms) throws InterruptedException
    {
        Thread.sleep(ms);
    }

    private void sleepUntilClosed(long ms)
    {
        long end = System.currentTimeMillis() + ms;
        int g = generation;
        while(!closed && System.currentTimeMillis() < end && generation == g)
        {
            try
            {
                Thread.sleep(100);
            }
            catch(InterruptedException e)
            {
                return;
            }
        }
    }

    // ================= 渲染线程 =================

    /**
     * 每帧调一次（渲染线程）。clock 是服务端状态换算的时钟；gain、x/y/z 给声音用；fullscreen 时声音用立体声。
     */
    public void update(TvClock clock, long nowMs, float gain, double x, double y, double z, boolean fullscreen)
    {
        long target = clock.targetAt(nowMs);
        boolean playing = clock.isPlaying();
        this.targetMs = target;
        if(clock.getReceivedAtMs() != lastStateAt)
        {
            // 服务端的状态变了（快进、后退、暂停…）：不等跳转的冷却，马上按新位置对齐
            lastStateAt = clock.getReceivedAtMs();
            lastSeekAt = 0;
        }
        if(durationMs > 0 && target >= durationMs)
        {
            if(status == Status.PLAYING)
                status = Status.ENDED;
            audio.stop();
            return;
        }
        if(status == Status.ENDED)
        {
            // 后退到片尾之前了：解码线程还开着（停在读完处），跳回去接着解
            status = Status.PLAYING;
            requestSeek(target, nowMs);
        }
        if(status != Status.PLAYING)
        {
            audio.stop();
            return;
        }
        int gen = generation;
        if(gen != seenGeneration)
        {
            seenGeneration = gen;
            syncPts = -1;
            audio.flush();
        }
        // 画面：取 pts 不晚于目标的最新一帧
        VideoFrame pending = null;
        VideoFrame f;
        while((f = videoQueue.peek()) != null)
        {
            if(f.generation != gen)
            {
                videoQueue.poll();
                recycle(f);
                continue;
            }
            if(f.ptsMs <= target + 15)
            {
                videoQueue.poll();
                if(pending != null)
                    recycle(pending);
                pending = f;
            }
            else
            {
                break;
            }
        }
        if(pending != null)
        {
            upload(pending);
            shownPts = pending.ptsMs;
            syncPts = pending.ptsMs;
            recycle(pending);
        }
        // 声音
        audio.pump(audioQueue, gen, target, playing, gain, x, y, z, fullscreen);
        // 对齐：偏差大就跳转
        long local = syncPts >= 0 ? syncPts : audio.position();
        if(local >= 0 && nowMs - lastSeekAt > SEEK_COOLDOWN_MS && !(videoEof && videoQueue.isEmpty() && local <= target))
        {
            if(TvClock.decide(local, target) == TvClock.Action.SEEK)
            {
                // 跳到目标再往后一点，抵消跳转本身的耗时
                requestSeek(target + (playing ? 300 : 0), nowMs);
            }
        }
        if(videoEof && videoQueue.isEmpty() && durationMs <= 0)
            status = Status.ENDED;
    }

    private void requestSeek(long toMs, long nowMs)
    {
        lastSeekAt = nowMs;
        seekToMs = Math.max(0, toMs);
        generation++;
    }

    private void upload(VideoFrame f)
    {
        if(texture < 0)
            texture = GlStateManager.generateTexture();
        GlStateManager.bindTexture(texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        if(f.width != texWidth || f.height != texHeight)
        {
            texWidth = f.width;
            texHeight = f.height;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, texWidth, texHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        f.data.position(0).limit(f.width * f.height * 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, f.width, f.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, f.data);
    }

    /** 本地正在播的位置（毫秒，日志、测同步用；没有时 -1） */
    public long localPositionMs()
    {
        return syncPts >= 0 ? syncPts : audio.position();
    }

    /** 关掉（渲染线程）：停线程、释放纹理和声音 */
    public void close()
    {
        closed = true;
        synchronized(threads)
        {
            for(Thread t : threads)
                t.interrupt();
        }
        if(texture >= 0)
        {
            GlStateManager.deleteTexture(texture);
            texture = -1;
        }
        audio.release();
        videoQueue.clear();
        freeFrames.clear();
        audioQueue.clear();
    }
}
