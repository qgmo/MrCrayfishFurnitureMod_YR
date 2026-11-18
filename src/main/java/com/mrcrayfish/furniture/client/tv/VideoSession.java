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
 * <p>
 * 直播（服主 11-18，{@link TvChannel#isLive()}）：没有进度，不看服务端的时钟，各人放自己收到的最新画面（{@link LiveClock}）。
 * 后台线程先查开播状态（{@link BiliApi#liveRoom}），没开播就停在 {@link Status#OFFLINE}（画测试卡、放“嘟——”声，{@link ToneOut}），
 * 每 {@value #OFFLINE_POLL_MS} 毫秒再查一次，弹幕连接收到“开播”时马上查；开播了取流（FLV 或 HLS-TS，经本机转发给 FFmpeg）一直解到流断，
 * 断了再从查开播状态开始。弹幕走直播弹幕连接（{@link LiveDanmakuClient}），来一条排一条（{@link Danmaku.Incremental}）。
 * <p>
 * 换清晰度（进出大屏，服主 11-18：无缝切换）：不重开整个视频。旧的一路照常放，后台按当前位置开新清晰度的视频（{@link #prepareVod}），
 * 解到对上当前位置的那一帧（{@link FrameSeek}）再交给解码线程，在下一帧换过去、关掉旧的视频解码（{@link #takePrepared}）。
 * 声音不动：DASH 的声音本来就是单独一路；单文件 MP4 的旧解码器改成只解声音继续用。直播的音视频在一条流里，换的时候音视频一起换。
 * 什么时候开始准备、作废、失败多久再试见 {@link QualitySwitch}。
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
        ERROR,
        /** 直播：没开播 / 下播了（画测试卡） */
        OFFLINE
    }

    /** 直播没开播时多久再查一次开播状态 */
    static final long OFFLINE_POLL_MS = 30_000L;
    /** 直播弹幕最多积压多少条没画的 */
    private static final int LIVE_PENDING = 256;
    /** 收到多久还没画的直播弹幕就不画了 */
    private static final long LIVE_STALE_MS = 3_000L;

    /** 画面队列最多这么多帧（480P 及以下）；更高清晰度时按 {@link #VIDEO_QUEUE_BYTES} 减少，见 {@link #queueFrames(int)} */
    private static final int VIDEO_QUEUE_MAX = 12;
    private static final int VIDEO_QUEUE_MIN = 4;
    /** 画面队列（含解码线程手里与正在显示的两帧）的内存预算，480P 以下的帧数不受它限制，1080P 时队列 6 帧、共约 66 MB */
    private static final long VIDEO_QUEUE_BYTES = 64L * 1024 * 1024;
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
    /** 清晰度：现在用的、想要的（进出大屏时无缝切换） */
    private final QualitySwitch quality;
    /** 画面队列按这一档算（玩家设置的生效上限）：切换清晰度时不用重建队列 */
    private final int queueQuality;
    private final boolean live;
    /** 准备好的新一路视频，等解码线程换上 */
    private final java.util.concurrent.atomic.AtomicReference<Prepared> prepared = new java.util.concurrent.atomic.AtomicReference<>();
    @Nullable
    private volatile Thread prepareThread;
    /** 解码线程打开着的解码器（结束时一起关） */
    private final List<VideoDecoder> openDecoders = Collections.synchronizedList(new ArrayList<>());
    /** 换清晰度后由旧的单文件 MP4 接着解声音的线程 */
    private final List<Thread> trackThreads = Collections.synchronizedList(new ArrayList<>());
    /** 只在解码线程用：最近放进队列的画面时间戳（换上新的一路时，不晚于它的帧丢掉，前后接得上） */
    private long lastQueuedPts = -1;
    private volatile long liveRoomId;

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
    private volatile boolean targetPlaying = true;
    /** 跳转请求：每次跳转 generation 加一，旧的帧丢掉 */
    private volatile long seekToMs = -1;
    private volatile int generation;

    private final int videoQueueSize;
    final BlockingQueue<VideoFrame> videoQueue;
    private final BlockingQueue<VideoFrame> freeFrames;
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
    /** 服务端状态变了、还没按新位置对齐过（一帧都没出时也要对齐） */
    private boolean stateChanged;
    private final AudioOut audio = new AudioOut();
    private int seenGeneration;
    private final java.util.Map<Integer, List<Danmaku.Placed>> layouts = new java.util.HashMap<>();
    private List<Danmaku.Comment> layoutSource;

    // ---- 直播 ----
    /** 弹幕连接收到“开播”：不用等到下一次轮询 */
    private volatile boolean liveWake;
    /** 弹幕连接收到“下播”：解码线程别等读超时，马上停 */
    private volatile boolean liveStop;
    @Nullable
    private volatile LiveDanmakuClient liveDanmaku;
    private final BlockingQueue<Danmaku.Comment> livePending = new ArrayBlockingQueue<>(LIVE_PENDING);
    /** 只在渲染线程用 */
    private final LiveClock liveClock = new LiveClock();
    /** 每种画布宽一份排版（世界里的电视和大屏同时在画，宽度不同）；最多留几份 */
    private final java.util.LinkedHashMap<Integer, Danmaku.Incremental> liveLayouts = new java.util.LinkedHashMap<>();
    /** 最近的直播弹幕（时间已经换成放到的位置）：新建一份排版时补进去 */
    private final List<Danmaku.Comment> liveRecent = new ArrayList<>();
    private static final int MAX_LIVE_LAYOUTS = 3;
    private final ToneOut tone = new ToneOut();

    /**
     * @param maxQuality   开始时的清晰度上限
     * @param queueQuality 画面队列按哪一档的帧大小算（玩家设置的生效上限，以后换清晰度都不超过它）
     */
    public VideoSession(BlockPos pos, TvChannel channel, int maxQuality, int queueQuality, long startTargetMs)
    {
        this.pos = pos;
        this.channel = channel;
        this.quality = new QualitySwitch(maxQuality);
        this.queueQuality = queueQuality;
        this.live = channel.isLive();
        this.videoQueueSize = queueFrames(Math.max(maxQuality, queueQuality));
        this.videoQueue = new ArrayBlockingQueue<>(videoQueueSize);
        this.freeFrames = new ArrayBlockingQueue<>(videoQueueSize + 4);
        this.targetMs = startTargetMs;
        Thread t = new Thread(this::run, "cfm-tv-decode-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        threads.add(t);
        t.start();
    }

    /** 清晰度上限下画面队列的帧数：预算内装得下就是 12，否则减到预算能装的数量，但不少于 4 */
    static int queueFrames(int maxQn)
    {
        long perFrame = VideoDecoder.maxFrameBytes(maxQn);
        long n = VIDEO_QUEUE_BYTES / perFrame - 2;
        return (int) Math.max(VIDEO_QUEUE_MIN, Math.min(VIDEO_QUEUE_MAX, n));
    }

    public boolean isLive()
    {
        return live;
    }

    /** 现在画面用的清晰度上限 */
    public int getMaxQuality()
    {
        return quality.current();
    }

    public int getQueueQuality()
    {
        return queueQuality;
    }

    /** 想要的清晰度上限（进出大屏时由 {@link TvVideoManager} 设）：不同于现在的就在后台无缝切换 */
    public void setQualityTarget(int cap)
    {
        quality.setTarget(cap);
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
        if(live)
        {
            runLive();
            return;
        }
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
                    if(targetPlaying)
                    {
                        status = Status.ENDED;
                        sleep(250);
                        continue;
                    }
                    // 停在片尾（暂停）：从片尾前一点开始解，画面停在最后一帧
                    seekToMs = Math.max(0, durationMs - 1500);
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
                // 网络、风控、代理的问题：视频信息还能用，只重取流地址
                if(!"cfm.tv.bili.network".equals(e.getKey()) && !"cfm.tv.bili.blocked".equals(e.getKey()) && !e.getKey().startsWith("cfm.tv.bili.proxy"))
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

    // ---- 直播的后台线程 ----

    private void runLive()
    {
        int failures = 0;
        while(!closed)
        {
            try
            {
                LiveData.Room room = BiliApi.liveRoom(channel.getRoom());
                liveRoomId = room.roomId;
                startLiveDanmaku(room.roomId);
                if(!room.isLive())
                {
                    goOffline();
                    continue;
                }
                BiliApi.LiveSource src = BiliApi.liveStream(room.roomId, quality.current());
                if(src.stream == null)
                {
                    goOffline();
                    continue;
                }
                long started = System.currentTimeMillis();
                playLive(src.stream);
                // 流断了（下播、网络、地址过期）：稍等再从查开播状态开始；连着很快断就等久一点（最多 30 秒）
                failures = System.currentTimeMillis() - started > 60_000L ? 0 : failures + 1;
                sleepLive(Math.min(30_000L, 1000L << Math.min(5, Math.max(0, failures - 1))));
            }
            catch(BiliData.BiliException e)
            {
                fail(e.getKey(), e.getMessage());
                sleepLive(30_000L);
            }
            catch(InterruptedException e)
            {
                break;
            }
            catch(Throwable t)
            {
                failures++;
                MrCrayfishFurnitureMod.logger().warn("TV live at {}: decoder error: {}", pos, t.toString());
                fail("cfm.tv.video.decode_error", t.toString());
                sleepLive(failures >= 3 ? 30_000L : 3_000L);
            }
        }
        releaseRelay();
    }

    private void goOffline()
    {
        if(status != Status.OFFLINE && !closed)
            MrCrayfishFurnitureMod.logger().info("TV live at {}: room {} is off air", pos, channel.getRoom());
        status = Status.OFFLINE;
        sleepLive(OFFLINE_POLL_MS);
    }

    /** 等 ms 毫秒，关掉或弹幕连接说开播了就提前醒 */
    private void sleepLive(long ms)
    {
        long end = System.currentTimeMillis() + ms;
        while(!closed && !liveWake && System.currentTimeMillis() < end)
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
        liveWake = false;
    }

    private void startLiveDanmaku(long roomId)
    {
        LiveDanmakuClient old = liveDanmaku;
        if(old != null && old.roomId() == roomId)
            return;
        if(old != null)
            old.close();
        if(closed)
            return;
        liveDanmaku = new LiveDanmakuClient(roomId, new LiveDanmakuClient.Listener()
        {
            @Override
            public void onComment(Danmaku.Comment comment)
            {
                // 时间先记成收到的本机时刻（排版时换成放到的位置）；积压满了（画不过来、或弹幕关着没人取）就丢掉
                livePending.offer(new Danmaku.Comment(System.currentTimeMillis(), comment.mode, comment.color, comment.text));
            }

            @Override
            public void onLive()
            {
                liveWake = true;
            }

            @Override
            public void onPreparing()
            {
                liveStop = true;
            }
        });
        if(closed)
            liveDanmaku.close();
    }

    /** 解一路直播，直到流断、下播、关掉 */
    private void playLive(LiveData.Stream stream) throws Exception
    {
        releaseRelay();
        String url = LocalRelay.registerLive(feed(stream));
        relayUrls.add(url);
        liveStop = false;
        int gen = ++generation;
        videoEof = false;
        VideoDecoder video = new VideoDecoder(url, true, true, 0, quality.current(), container(stream));
        openDecoders.add(video);
        try
        {
            hasAudio = video.hasAudio();
            status = Status.PLAYING;
            decodeLoop(video, gen, true);
        }
        finally
        {
            closeDecoders();
            discardPrepared();
            releaseRelay();
        }
    }

    private static VideoDecoder.Container container(LiveData.Stream stream)
    {
        return stream.isFlv() ? VideoDecoder.Container.FLV : VideoDecoder.Container.MPEGTS;
    }

    private static LocalRelay.LiveFeed feed(LiveData.Stream stream)
    {
        return stream.isFlv() ? flvFeed(stream.urls) : hlsFeed(stream.urls);
    }

    /** HTTP-FLV：依次试各个 CDN 地址，哪个回 200 用哪个 */
    private static LocalRelay.LiveFeed flvFeed(List<URI> urls)
    {
        return new LocalRelay.LiveFeed()
        {
            @Override
            public java.io.InputStream open() throws IOException
            {
                IOException last = null;
                for(URI u : urls)
                {
                    try
                    {
                        TvHttp.Response res = TvHttp.get(u, HostPolicy.Purpose.CDN, "*/*", -1, -1, -1, 20_000);
                        if(res.status == 200)
                            return closing(res);
                        last = new IOException("HTTP " + res.status + " from " + TvHttp.safe(res.uri));
                        res.close();
                    }
                    catch(IOException e)
                    {
                        last = e;
                    }
                }
                throw last != null ? last : new IOException("no live stream address");
            }

            @Override
            public String contentType()
            {
                return "video/x-flv";
            }
        };
    }

    /** HLS-TS：分片在 Java 里取、接成一条流（{@link HlsFeed}） */
    private static LocalRelay.LiveFeed hlsFeed(List<URI> playlists)
    {
        HlsFeed.Fetcher fetcher = new HlsFeed.Fetcher()
        {
            @Override
            public String playlist(URI uri) throws IOException
            {
                return TvHttp.getText(uri, HostPolicy.Purpose.CDN, 1024 * 1024);
            }

            @Override
            public java.io.InputStream segment(URI uri) throws IOException
            {
                TvHttp.Response res = TvHttp.get(uri, HostPolicy.Purpose.CDN, "*/*", -1, -1, 64L * 1024 * 1024, 20_000);
                if(res.status != 200)
                {
                    res.close();
                    throw new IOException("HTTP " + res.status + " from " + TvHttp.safe(res.uri));
                }
                return closing(res);
            }
        };
        return new LocalRelay.LiveFeed()
        {
            @Override
            public java.io.InputStream open()
            {
                return new HlsFeed(playlists, fetcher, Thread::sleep);
            }

            @Override
            public String contentType()
            {
                return "video/mp2t";
            }
        };
    }

    /** 响应体，关的时候连同连接一起关 */
    private static java.io.InputStream closing(TvHttp.Response res) throws IOException
    {
        return new java.io.FilterInputStream(res.body())
        {
            @Override
            public void close() throws IOException
            {
                try
                {
                    super.close();
                }
                finally
                {
                    res.close();
                }
            }
        };
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
        BiliData.Stream s = BiliApi.playurl(i, quality.current());
        stream = s;
        streamAt = System.currentTimeMillis();
        if(s.durationMs > 0 && durationMs <= 0)
            durationMs = s.durationMs;
    }

    /**
     * 本机转发的一路（画面或声音）：记着自己的流地址与清晰度，地址过期时按同一档重新取。换清晰度后新旧两路各用各的。
     */
    private final class Track implements LocalRelay.Source
    {
        private final boolean audioTrack;
        private final int cap;
        private volatile BiliData.Stream current;
        private long at;

        Track(BiliData.Stream stream, boolean audioTrack, int cap)
        {
            this.current = stream;
            this.audioTrack = audioTrack;
            this.cap = cap;
            this.at = System.currentTimeMillis();
        }

        @Override
        public List<URI> urls()
        {
            BiliData.Stream s = current;
            return s == null ? Collections.emptyList() : audioTrack ? s.audio : s.video;
        }

        @Override
        public synchronized void refresh() throws IOException
        {
            if(System.currentTimeMillis() - at < 10_000L)
                return;
            BiliData.VideoInfo i = info;
            if(i == null)
                return;
            try
            {
                BiliData.Stream s = BiliApi.playurl(i, cap);
                // 单文件 MP4 换成了 DASH（或反过来）：不是同一个文件，接不上
                if(current != null && s.dash != current.dash)
                    throw new IOException("stream type changed");
                current = s;
                at = System.currentTimeMillis();
            }
            catch(BiliData.BiliException e)
            {
                throw new IOException(e.getMessage());
            }
        }
    }

    /** 打开解码器从目标位置开始解，直到读完、出错或关掉 */
    private void playOnce() throws Exception
    {
        BiliData.Stream s = stream;
        if(s == null)
            return;
        releaseRelay();
        int cap = quality.current();
        String videoUrl = LocalRelay.register(new Track(s, false, cap));
        relayUrls.add(videoUrl);
        String audioUrl = null;
        if(s.dash && !s.audio.isEmpty())
        {
            audioUrl = LocalRelay.register(new Track(s, true, cap));
            relayUrls.add(audioUrl);
        }
        int gen = generation;
        long start = seekToMs >= 0 ? seekToMs : targetMs;
        seekToMs = -1;
        videoEof = false;
        lastQueuedPts = -1;
        VideoDecoder video = new VideoDecoder(videoUrl, true, !s.dash, start, cap);
        openDecoders.add(video);
        try
        {
            if(durationMs <= 0)
                durationMs = video.lengthMs();
            hasAudio = video.hasAudio();
            if(audioUrl != null)
            {
                hasAudio = true;
                String url = audioUrl;
                Thread audioThread = new Thread(() -> audioLoop(url, start, gen), "cfm-tv-audio-" + THREADS.incrementAndGet());
                audioThread.setDaemon(true);
                audioThread.setPriority(Thread.NORM_PRIORITY - 1);
                threads.add(audioThread);
                trackThreads.add(audioThread);
                audioThread.start();
            }
            status = Status.PLAYING;
            decodeLoop(video, gen, true);
        }
        finally
        {
            stopTrackThreads();
            closeDecoders();
            discardPrepared();
        }
    }

    private void stopTrackThreads()
    {
        List<Thread> list;
        synchronized(trackThreads)
        {
            list = new ArrayList<>(trackThreads);
            trackThreads.clear();
        }
        for(Thread t : list)
            t.interrupt();
        for(Thread t : list)
        {
            try
            {
                t.join(2000);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            threads.remove(t);
        }
    }

    private void closeDecoders()
    {
        List<VideoDecoder> list;
        synchronized(openDecoders)
        {
            list = new ArrayList<>(openDecoders);
            openDecoders.clear();
        }
        for(VideoDecoder d : list)
            d.close();
    }

    // ---- 无缝换清晰度（服主 11-18） ----

    /** 准备好的新一路视频：解码器已经停在对上当前位置的那一帧之后 */
    private static final class Prepared
    {
        final int attempt;
        final int generation;
        final int cap;
        final VideoDecoder decoder;
        final String relayUrl;
        final VideoFrame first;
        @Nullable
        final BiliData.Stream stream;

        Prepared(int attempt, int generation, int cap, VideoDecoder decoder, String relayUrl, VideoFrame first, @Nullable BiliData.Stream stream)
        {
            this.attempt = attempt;
            this.generation = generation;
            this.cap = cap;
            this.decoder = decoder;
            this.relayUrl = relayUrl;
            this.first = first;
            this.stream = stream;
        }

        void close()
        {
            decoder.close();
            LocalRelay.unregister(relayUrl);
        }
    }

    private void discardPrepared()
    {
        Prepared p = prepared.getAndSet(null);
        if(p != null)
            p.close();
    }

    private void publish(Prepared p)
    {
        Prepared old = prepared.getAndSet(p);
        if(old != null)
            old.close();
        if(closed)
            discardPrepared();
    }

    /** 渲染线程每帧：按 {@link QualitySwitch} 的决定开始准备新的一路。同一台电视同一时间最多一个准备线程 */
    private void pollQuality(long nowMs)
    {
        if(status != Status.PLAYING || !hasPicture())
            return;
        QualitySwitch.Decision d = quality.poll(nowMs);
        if(d != QualitySwitch.Decision.START && d != QualitySwitch.Decision.RESTART)
            return;
        int attempt = quality.preparingAttempt();
        int cap = quality.preparingQuality();
        Thread old = prepareThread;
        if(old != null && old.isAlive())
        {
            // 上一次（已作废）的准备还没退出：不叠着开，下一帧再说
            quality.interrupted(attempt);
            return;
        }
        Thread t = new Thread(() ->
        {
            try
            {
                if(live)
                    prepareLive(attempt, cap);
                else
                    prepareVod(attempt, cap);
            }
            finally
            {
                threads.remove(Thread.currentThread());
            }
        }, "cfm-tv-switch-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        prepareThread = t;
        threads.add(t);
        t.start();
    }

    /** 准备时最多解多少帧还没对上就算失败（约 30 秒的画面） */
    private static final int MAX_PREPARE_FRAMES = 1800;

    /** 点播：按新清晰度取流，只开画面，从当前位置前最近的关键帧解到对上当前位置 */
    private void prepareVod(int attempt, int cap)
    {
        int gen = generation;
        VideoDecoder dec = null;
        String url = null;
        boolean handed = false;
        java.util.function.BooleanSupplier cancelled = () -> closed || generation != gen || !quality.valid(attempt);
        try
        {
            BiliData.VideoInfo i = info;
            if(i == null)
            {
                quality.interrupted(attempt);
                return;
            }
            BiliData.Stream s = BiliApi.playurl(i, cap);
            if(cancelled.getAsBoolean())
                return;
            url = LocalRelay.register(new Track(s, false, cap));
            dec = new VideoDecoder(url, true, false, Math.max(0, targetMs), cap);
            VideoFrame first = seekFirst(dec, () -> targetMs, cancelled);
            if(first == null)
            {
                if(generation != gen)
                    quality.interrupted(attempt);
                else if(!closed && quality.valid(attempt))
                    quality.failed(attempt, System.currentTimeMillis());
                return;
            }
            first.generation = gen;
            publish(new Prepared(attempt, gen, cap, dec, url, first, s));
            handed = true;
        }
        catch(InterruptedException e)
        {
            quality.interrupted(attempt);
        }
        catch(Throwable t)
        {
            MrCrayfishFurnitureMod.logger().info("TV video at {}: switching to quality {} failed, keeping the current one: {}", pos, cap, t instanceof BiliData.BiliException ? ((BiliData.BiliException) t).getKey() : t.toString());
            quality.failed(attempt, System.currentTimeMillis());
        }
        finally
        {
            if(!handed)
            {
                if(dec != null)
                    dec.close();
                LocalRelay.unregister(url);
            }
        }
    }

    /** 直播：按新清晰度重新取流（音视频在一条流里），解出第一帧画面就算准备好 */
    private void prepareLive(int attempt, int cap)
    {
        VideoDecoder dec = null;
        String url = null;
        boolean handed = false;
        java.util.function.BooleanSupplier cancelled = () -> closed || status != Status.PLAYING || !quality.valid(attempt);
        try
        {
            long room = liveRoomId;
            if(room <= 0)
            {
                quality.interrupted(attempt);
                return;
            }
            BiliApi.LiveSource src = BiliApi.liveStream(room, cap);
            if(src.stream == null)
            {
                quality.failed(attempt, System.currentTimeMillis());
                return;
            }
            if(cancelled.getAsBoolean())
                return;
            url = LocalRelay.registerLive(feed(src.stream));
            dec = new VideoDecoder(url, true, true, 0, cap, container(src.stream));
            VideoFrame first = seekFirst(dec, () -> Long.MIN_VALUE / 2, cancelled);
            if(first == null)
            {
                if(!closed && quality.valid(attempt))
                    quality.failed(attempt, System.currentTimeMillis());
                return;
            }
            publish(new Prepared(attempt, generation, cap, dec, url, first, null));
            handed = true;
        }
        catch(InterruptedException e)
        {
            quality.interrupted(attempt);
        }
        catch(Throwable t)
        {
            MrCrayfishFurnitureMod.logger().info("TV live at {}: switching to quality {} failed, keeping the current one: {}", pos, cap, t instanceof BiliData.BiliException ? ((BiliData.BiliException) t).getKey() : t.toString());
            quality.failed(attempt, System.currentTimeMillis());
        }
        finally
        {
            if(!handed)
            {
                if(dec != null)
                    dec.close();
                LocalRelay.unregister(url);
            }
        }
    }

    /** 解到对上 target 的那一帧画面，拷进一个新的帧缓冲 */
    @Nullable
    private VideoFrame seekFirst(VideoDecoder dec, java.util.function.LongSupplier target, java.util.function.BooleanSupplier cancelled) throws Exception
    {
        Frame f = FrameSeek.seek(new FrameSeek.Source<Frame>()
        {
            @Override
            public Frame next() throws Exception
            {
                while(true)
                {
                    Frame fr = dec.next();
                    if(fr == null || (VideoDecoder.isImage(fr) && fr.imageWidth > 0 && fr.imageHeight > 0))
                        return fr;
                }
            }

            @Override
            public long ptsMs(Frame frame)
            {
                return frame.timestamp / 1000L;
            }
        }, target, cancelled, MAX_PREPARE_FRAMES);
        if(f == null)
            return null;
        VideoFrame vf = new VideoFrame(f.imageWidth * f.imageHeight * 4);
        VideoDecoder.copyImage(f, vf.data);
        vf.width = f.imageWidth;
        vf.height = f.imageHeight;
        vf.ptsMs = f.timestamp / 1000L;
        return vf;
    }

    /**
     * 解码线程：有准备好的新一路就换上（返回新的解码器），否则返回 current。作废了的（目标又变了、跳转过进度）关掉。
     */
    private VideoDecoder takePrepared(VideoDecoder current) throws InterruptedException
    {
        Prepared p = prepared.getAndSet(null);
        if(p == null)
            return current;
        if(!live && p.generation != generation)
        {
            // 准备期间跳转过进度：位置对不上了，重新准备
            quality.interrupted(p.attempt);
            p.close();
            return current;
        }
        if(!quality.ready(p.attempt))
        {
            p.close();
            return current;
        }
        quality.handedOver(p.attempt);
        relayUrls.add(p.relayUrl);
        openDecoders.add(p.decoder);
        if(live)
        {
            // 直播的音视频在一条流里：一起换，渲染线程按新的一代重新对时钟（声音会断一下）
            openDecoders.remove(current);
            current.close();
            int gen = ++generation;
            p.first.generation = gen;
            hasAudio = p.decoder.hasAudio();
            if(!videoQueue.offer(p.first))
                recycle(p.first);
        }
        else
        {
            if(current.audioEnabled())
            {
                // 单文件 MP4：旧的解码器不再解画面，接着解声音（声音不断）
                current.setVideoEnabled(false);
                int g = generation;
                Thread t = new Thread(() ->
                {
                    try
                    {
                        decodeLoop(current, g, false);
                    }
                    catch(InterruptedException e)
                    {
                        // 结束
                    }
                    catch(Throwable e)
                    {
                        MrCrayfishFurnitureMod.logger().info("TV video at {}: audio stopped: {}", pos, e.toString());
                    }
                }, "cfm-tv-audio-" + THREADS.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                threads.add(t);
                trackThreads.add(t);
                t.start();
            }
            else
            {
                // DASH：声音本来就是单独一路，旧的画面解码直接关
                openDecoders.remove(current);
                current.close();
            }
            p.first.generation = generation;
            if(p.first.ptsMs > lastQueuedPts && videoQueue.offer(p.first))
                lastQueuedPts = p.first.ptsMs;
            else
                recycle(p.first);
            if(p.stream != null)
            {
                stream = p.stream;
                streamAt = System.currentTimeMillis();
            }
        }
        MrCrayfishFurnitureMod.logger().info("TV video at {}: switched to quality {}", pos, p.cap);
        return p.decoder;
    }

    /** DASH 的声音单独一路；出错（例如地址过期、网络断了）时隔一会儿从当前位置重开，连续失败 5 次才放弃 */
    private void audioLoop(String url, long start, int startGen)
    {
        int failures = 0;
        long from = start;
        int gen = startGen;
        while(!closed && !Thread.currentThread().isInterrupted())
        {
            try(VideoDecoder decoder = new VideoDecoder(url, false, true, from, quality.current()))
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
            if(live && liveStop)
                return;
            if(main)
            {
                VideoDecoder next = takePrepared(decoder);
                if(next != decoder)
                {
                    decoder = next;
                    eof = false;
                    videoEof = false;
                    if(live)
                        gen = generation;
                }
            }
            int g = generation;
            if(g != gen && live)
            {
                // 直播不跳转
                gen = g;
            }
            else if(g != gen)
            {
                long to = Math.max(0, seekToMs >= 0 ? seekToMs : targetMs);
                decoder.seek(to);
                gen = g;
                eof = false;
                if(main)
                {
                    videoEof = false;
                    lastQueuedPts = -1;
                }
            }
            if(eof && live)
            {
                // 直播流断了：交回后台线程重新查开播状态
                return;
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
                if(main && !live && pts <= lastQueuedPts)
                {
                    // 刚换上新的一路：旧的已经放到这里了，接着往后放
                    continue;
                }
                VideoFrame vf = takeFree(f.imageWidth * f.imageHeight * 4);
                if(vf == null)
                    return;
                VideoDecoder.copyImage(f, vf.data);
                vf.width = f.imageWidth;
                vf.height = f.imageHeight;
                vf.ptsMs = pts;
                vf.generation = gen;
                boolean offered = false;
                while(!closed && generation == gen && !(main && prepared.get() != null) && !(offered = videoQueue.offer(vf, 50, TimeUnit.MILLISECONDS)))
                {
                    // 队列满（暂停、或解得比播得快）：等；有准备好的新一路时不等，先去换上
                }
                if(!offered)
                    recycle(vf);
                else if(main)
                    lastQueuedPts = pts;
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
            if(f == null && allocated.get() < videoQueueSize + 2)
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
        if(live)
        {
            updateLive(nowMs, gain, x, y, z, fullscreen);
            return;
        }
        long target = clock.targetAt(nowMs);
        boolean playing = clock.isPlaying();
        this.targetMs = target;
        this.targetPlaying = playing;
        if(clock.getReceivedAtMs() != lastStateAt)
        {
            // 服务端的状态变了（快进、后退、暂停…）：不等跳转的冷却，马上按新位置对齐
            lastStateAt = clock.getReceivedAtMs();
            lastSeekAt = 0;
            stateChanged = true;
        }
        boolean atEnd = durationMs > 0 && target >= durationMs;
        if(atEnd && playing)
        {
            if(status == Status.PLAYING)
                status = Status.ENDED;
            audio.stop();
            return;
        }
        if(atEnd && status == Status.ENDED)
        {
            // 服务端让它停在片尾（暂停）：画面留在最后一帧，不回到噪点
            status = Status.PLAYING;
        }
        else if(status == Status.ENDED)
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
        pollQuality(nowMs);
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
        boolean resync = stateChanged;
        stateChanged = false;
        if(local < 0 && resync && videoEof && videoQueue.isEmpty())
        {
            // 解码线程停在读完处、一帧也没出（例如之前的位置在流的末尾之后），服务端又跳回前面了：照新位置重新解
            requestSeek(target + (playing ? 300 : 0), nowMs);
        }
        else if(local >= 0 && nowMs - lastSeekAt > SEEK_COOLDOWN_MS && !(videoEof && videoQueue.isEmpty() && local <= target))
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

    /** 直播：按本地时钟放最新收到的画面与声音；没开播时放测试卡的“嘟——”声（本地配置能关） */
    private void updateLive(long nowMs, float gain, double x, double y, double z, boolean fullscreen)
    {
        if(status == Status.OFFLINE)
        {
            audio.stop();
            if(gain > 0 && TvClientConfig.get().offlineTone)
                tone.play(gain, x, y, z, fullscreen);
            else
                tone.stop();
            return;
        }
        tone.stop();
        if(status != Status.PLAYING)
        {
            audio.stop();
            return;
        }
        pollQuality(nowMs);
        int gen = generation;
        if(gen != seenGeneration)
        {
            // 换了一路流（重连、换清晰度）：时钟重新对，弹幕的排版按新的时钟重来
            seenGeneration = gen;
            liveClock.reset();
            liveRecent.clear();
            liveLayouts.clear();
            syncPts = -1;
            audio.flush();
        }
        VideoFrame head;
        while((head = videoQueue.peek()) != null && head.generation != gen)
        {
            videoQueue.poll();
            recycle(head);
        }
        if(head != null)
        {
            liveClock.observe(head.ptsMs, nowMs);
        }
        else
        {
            AudioChunk a = audioQueue.peek();
            if(a != null && a.generation == gen)
                liveClock.observe(a.ptsMs, nowMs);
        }
        if(!liveClock.started())
            return;
        long target = liveClock.target(nowMs);
        this.targetMs = target;
        VideoFrame pending = null;
        VideoFrame f;
        while((f = videoQueue.peek()) != null && f.ptsMs <= target + 15)
        {
            videoQueue.poll();
            if(f.generation != gen)
            {
                recycle(f);
                continue;
            }
            if(pending != null)
                recycle(pending);
            pending = f;
        }
        if(pending != null)
        {
            upload(pending);
            shownPts = pending.ptsMs;
            syncPts = pending.ptsMs;
            recycle(pending);
        }
        audio.pump(audioQueue, gen, target, true, gain, x, y, z, fullscreen);
    }

    /**
     * 直播弹幕（渲染线程）：把新到的排进去（时间记成现在放到的位置），返回此刻要画的。
     * 世界里的电视和大屏在同一帧里都会画（画布宽不同），每种宽各排一份，新到的弹幕同时排进每一份；新出现的宽度把最近的弹幕补排进去。
     */
    public List<Danmaku.Visible> liveDanmaku(int canvasWidth, int canvasHeight, int lanes, int lineHeight, java.util.function.ToIntFunction<String> width, long nowMs)
    {
        long t = liveClock.target(nowMs);
        if(!liveClock.started() || status != Status.PLAYING)
        {
            livePending.clear();
            return Collections.emptyList();
        }
        int cw = Math.max(1, canvasWidth);
        Danmaku.Comment c;
        while((c = livePending.poll()) != null)
        {
            // 积压太久的（弹幕关着时攒下的）不画
            if(nowMs - c.timeMs > LIVE_STALE_MS)
                continue;
            Danmaku.Comment timed = new Danmaku.Comment(t, c.mode, c.color, c.text);
            liveRecent.add(timed);
            for(Danmaku.Incremental layout : liveLayouts.values())
                layout.add(timed, width.applyAsInt(timed.text));
        }
        long oldest = t - Danmaku.SCROLL_MS - 1000;
        int drop = 0;
        while(drop < liveRecent.size() && liveRecent.get(drop).timeMs < oldest)
            drop++;
        if(drop > 0)
            liveRecent.subList(0, drop).clear();
        Danmaku.Incremental layout = liveLayouts.get(cw);
        if(layout == null)
        {
            if(liveLayouts.size() >= MAX_LIVE_LAYOUTS)
                liveLayouts.remove(liveLayouts.keySet().iterator().next());
            layout = new Danmaku.Incremental(cw, lanes);
            for(Danmaku.Comment r : liveRecent)
                layout.add(r, width.applyAsInt(r.text));
            liveLayouts.put(cw, layout);
        }
        layout.trimBefore(oldest);
        return Danmaku.visibleAt(layout.placed(), t, canvasWidth, canvasHeight, lineHeight);
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
        tone.release();
        discardPrepared();
        LiveDanmakuClient d = liveDanmaku;
        if(d != null)
            d.close();
        livePending.clear();
        videoQueue.clear();
        freeFrames.clear();
        audioQueue.clear();
    }
}
