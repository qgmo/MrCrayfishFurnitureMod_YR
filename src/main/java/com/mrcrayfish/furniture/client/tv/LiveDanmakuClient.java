package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个直播间的弹幕连接（悠然服务器魔改，服主 11-18，只在客户端的后台线程用）。连接方式参考 PiliPlus（GPL-3.0，只借鉴思路）
 * 的 {@code lib/tcp/live.dart} 与 {@code lib/pages/live_room/controller_mixin/dm_stream.dart}：
 * <ol>
 * <li>{@code getDanmuInfo}（WBI 签名）取令牌与服务器列表，依次试 {@code wss://<服务器>:443/sub}（{@link LiveSocket}）；</li>
 * <li>连上后发认证包（操作码 7，匿名 uid 0、{@code protover 2} 即 zlib，带 buvid 与令牌），收到认证回应（操作码 8）后每 30 秒发一次心跳（操作码 2）；</li>
 * <li>收到的消息（操作码 5，多半 zlib 压缩，见 {@link LivePackets}）里只取弹幕文字、开播、下播。</li>
 * </ol>
 * 断了自动重连，等的时间有上限地翻倍（{@link Reconnect}）；70 秒收不到任何东西（服务器每次心跳都会回）算断了。
 * 弹幕关着时也连着：开播、下播的通知要用（开播后马上开始播）。
 */
final class LiveDanmakuClient
{
    static final long HEARTBEAT_MS = 30_000L;
    private static final int READ_TIMEOUT_MS = 70_000;
    private static final AtomicInteger THREADS = new AtomicInteger();

    interface Listener
    {
        void onComment(Danmaku.Comment comment);

        void onLive();

        void onPreparing();
    }

    private final long roomId;
    private final Listener listener;
    private volatile boolean closed;
    private volatile LiveSocket socket;
    private final Thread thread;

    LiveDanmakuClient(long roomId, Listener listener)
    {
        this.roomId = roomId;
        this.listener = listener;
        this.thread = new Thread(this::run, "cfm-tv-live-danmaku-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
    }

    long roomId()
    {
        return roomId;
    }

    private void run()
    {
        Reconnect.loop(this::connectOnce, () -> closed, System::currentTimeMillis, Thread::sleep,
                e -> MrCrayfishFurnitureMod.logger().info("TV live danmaku for room {}: {}", roomId, e instanceof BiliData.BiliException ? ((BiliData.BiliException) e).getKey() + " " + e.getMessage() : e.toString()));
    }

    private void connectOnce() throws Exception
    {
        LiveData.DanmuInfo info = BiliApi.liveDanmuInfo(roomId);
        List<String> hosts = info.hosts;
        if(hosts.isEmpty())
            throw new IOException("no danmaku servers");
        IOException last = null;
        LiveSocket s = null;
        for(String host : hosts)
        {
            if(closed)
                return;
            try
            {
                s = LiveSocket.connect(host, TvHttp.CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                break;
            }
            catch(IOException e)
            {
                last = e;
            }
        }
        if(s == null)
            throw last != null ? last : new IOException("no danmaku servers");
        socket = s;
        Thread heartbeat = null;
        try
        {
            String buvid = TvCookies.buvid3(TvClientConfig.get().cookie);
            s.sendBinary(LivePackets.encode(LivePackets.OP_AUTH, 1, LivePackets.authBody(roomId, buvid, info.token)));
            boolean authed = false;
            while(!closed)
            {
                byte[] message;
                try
                {
                    message = s.readMessage();
                }
                catch(SocketTimeoutException e)
                {
                    throw new IOException("no data for " + READ_TIMEOUT_MS / 1000 + " s");
                }
                if(message == null)
                    return;
                for(LivePackets.Packet p : LivePackets.decode(message))
                {
                    if(p.op == LivePackets.OP_AUTH_REPLY && !authed)
                    {
                        authed = true;
                        heartbeat = startHeartbeat(s);
                    }
                    else if(p.op == LivePackets.OP_MESSAGE)
                    {
                        LivePackets.Event ev = LivePackets.parseMessage(p.text());
                        if(ev == null)
                            continue;
                        switch(ev.type)
                        {
                            case COMMENT:
                                listener.onComment(ev.comment);
                                break;
                            case LIVE:
                                listener.onLive();
                                break;
                            case PREPARING:
                                listener.onPreparing();
                                break;
                        }
                    }
                }
            }
        }
        finally
        {
            if(heartbeat != null)
                heartbeat.interrupt();
            socket = null;
            s.close();
        }
    }

    private Thread startHeartbeat(LiveSocket s)
    {
        Thread t = new Thread(() ->
        {
            int seq = 2;
            try
            {
                while(!closed && !Thread.currentThread().isInterrupted())
                {
                    s.sendBinary(LivePackets.encode(LivePackets.OP_HEARTBEAT, seq++, new byte[0]));
                    Thread.sleep(HEARTBEAT_MS);
                }
            }
            catch(IOException | InterruptedException e)
            {
                // 连接断了由读线程处理
            }
        }, "cfm-tv-live-heartbeat");
        t.setDaemon(true);
        t.start();
        return t;
    }

    void close()
    {
        closed = true;
        thread.interrupt();
        LiveSocket s = socket;
        if(s != null)
            s.close();
    }
}
