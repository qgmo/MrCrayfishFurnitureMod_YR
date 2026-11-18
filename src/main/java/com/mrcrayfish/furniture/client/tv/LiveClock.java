package com.mrcrayfish.furniture.client.tv;

/**
 * 直播的本地时钟（纯逻辑，悠然服务器魔改，服主 11-18）。直播没有服务端的进度，各人看自己收到的最新画面：
 * 收到第一帧时记下“这一帧的时间戳 ↔ 本地时刻”，之后按本地经过的时间往前走，晚 {@value #DELAY_MS} 毫秒放（留一点缓冲，抖动时不卡）。
 * 队列最前面那一帧和时钟差出 {@value #RESYNC_MS} 毫秒以上（网络卡了一阵、流重连后时间戳跳了）就以它为准重新对上。
 */
final class LiveClock
{
    static final long DELAY_MS = 300L;
    static final long RESYNC_MS = 2_500L;

    private boolean started;
    private long basePts;
    private long baseWall;

    /**
     * 本地时刻 now 时应当放到哪个时间戳（还没收到帧时没有意义，先看 {@link #started()}）。
     * 直播流的时间戳可能从 0 附近开始，所以算出来可以是负数。
     */
    long target(long now)
    {
        return basePts + Math.max(0, now - baseWall) - DELAY_MS;
    }

    /** 看到队列最前面的一帧（时间戳 headPts）：第一次、或者差太多时重新对上 */
    void observe(long headPts, long now)
    {
        if(!started || Math.abs(headPts - target(now)) > RESYNC_MS)
        {
            started = true;
            basePts = headPts;
            baseWall = now;
        }
    }

    /** 重新连流时：从头对 */
    void reset()
    {
        started = false;
    }

    boolean started()
    {
        return started;
    }
}
