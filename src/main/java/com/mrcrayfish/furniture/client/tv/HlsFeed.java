package com.mrcrayfish.furniture.client.tv;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 把 HLS 直播的 TS 分片接成一条连续的字节流（悠然服务器魔改，服主 11-18 直播），交给本机转发再给 FFmpeg：
 * FFmpeg 只看到一条 mpegts 流，不用开它自己的 HLS 解复用器去联网（分片、列表都由模组的 Java 代码按白名单取）。
 * <ul>
 * <li>第一次从列表里倒数第 {@value #START_BACK} 个分片开始（接近直播的最新画面，又留一点缓冲）；</li>
 * <li>之后每个分片只取一次（按序号），列表里没有新的就隔半个目标时长再取列表；</li>
 * <li>第一次取列表时，一个地址取不到（11-18 实测有的 CDN 节点取列表会一直不回应、读超时）就按顺序换下一个地址；</li>
 * <li>{@value #MAX_IDLE_MS} 毫秒都没有新分片（下播、断流），或列表写了结束：读到结尾，由调用方重新查开播状态。</li>
 * </ul>
 * 取列表、取分片、等待都通过接口传进来，单元测试用假的（不联网、不真的等）。
 */
final class HlsFeed extends InputStream
{
    static final int START_BACK = 3;
    static final long MAX_IDLE_MS = 30_000L;

    interface Fetcher
    {
        String playlist(URI uri) throws IOException;

        InputStream segment(URI uri) throws IOException;
    }

    interface Sleeper
    {
        void sleep(long ms) throws InterruptedException;
    }

    private final Fetcher fetcher;
    private final Sleeper sleeper;
    private URI playlistUri;
    /** 还没取到过列表时可以换的地址 */
    private final Deque<URI> alternatives = new ArrayDeque<>();
    private boolean loadedOnce;
    private final Deque<HlsPlaylist.Segment> pending = new ArrayDeque<>();
    private long lastSeq = Long.MIN_VALUE;
    private InputStream current;
    private boolean finished;
    private volatile boolean closed;
    /** 取过几次列表（测试用） */
    int playlistLoads;

    HlsFeed(URI playlistUri, Fetcher fetcher, Sleeper sleeper)
    {
        this(java.util.Collections.singletonList(playlistUri), fetcher, sleeper);
    }

    /** playlists：按顺序的候选列表地址（不同 CDN 节点） */
    HlsFeed(java.util.List<URI> playlists, Fetcher fetcher, Sleeper sleeper)
    {
        if(playlists.isEmpty())
            throw new IllegalArgumentException("no playlist");
        this.playlistUri = playlists.get(0);
        this.alternatives.addAll(playlists.subList(1, playlists.size()));
        this.fetcher = fetcher;
        this.sleeper = sleeper;
    }

    @Override
    public int read() throws IOException
    {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n <= 0 ? -1 : b[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        if(len == 0)
            return 0;
        while(!closed)
        {
            if(current == null && !nextSegment())
                return -1;
            int n = current.read(b, off, len);
            if(n > 0)
                return n;
            current.close();
            current = null;
        }
        return -1;
    }

    /** 打开下一个分片；没有了返回 false */
    private boolean nextSegment() throws IOException
    {
        long idle = 0;
        while(!closed && !finished)
        {
            HlsPlaylist.Segment next = pending.pollFirst();
            if(next != null)
            {
                lastSeq = next.seq;
                current = fetcher.segment(next.uri);
                return true;
            }
            HlsPlaylist list = load();
            for(HlsPlaylist.Segment s : list.segments)
            {
                if(s.seq > lastSeq)
                    pending.addLast(s);
            }
            if(!pending.isEmpty())
                continue;
            if(list.ended)
            {
                finished = true;
                break;
            }
            long wait = Math.max(250, list.targetDurationMs / 2);
            idle += wait;
            if(idle > MAX_IDLE_MS)
            {
                finished = true;
                break;
            }
            try
            {
                sleeper.sleep(wait);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        return false;
    }

    private HlsPlaylist load() throws IOException
    {
        playlistLoads++;
        String text;
        while(true)
        {
            try
            {
                text = fetcher.playlist(playlistUri);
                break;
            }
            catch(IOException e)
            {
                if(loadedOnce || alternatives.isEmpty())
                    throw e;
                playlistUri = alternatives.pollFirst();
            }
        }
        loadedOnce = true;
        HlsPlaylist list = HlsPlaylist.parse(text, playlistUri);
        if(list.variant != null)
        {
            // 主列表：换成第一路再取一次
            playlistUri = list.variant;
            list = HlsPlaylist.parse(fetcher.playlist(playlistUri), playlistUri);
            if(list.variant != null)
                throw new IOException("nested HLS master playlists");
        }
        if(lastSeq == Long.MIN_VALUE && !list.segments.isEmpty())
        {
            // 第一次：从倒数第 START_BACK 个开始
            int from = Math.max(0, list.segments.size() - START_BACK);
            lastSeq = list.segments.get(from).seq - 1;
        }
        return list;
    }

    @Override
    public void close() throws IOException
    {
        closed = true;
        if(current != null)
            current.close();
    }
}
