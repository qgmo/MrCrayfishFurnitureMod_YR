package com.mrcrayfish.furniture.tv;

/**
 * 客户端这边的“目标位置”（纯逻辑，悠然服务器魔改）。服务端每次同步发来“发出时的当前位置”和播放 / 暂停；
 * 客户端和服务端的时钟对不上，所以不用服务端的基准时刻，而是记下收到的本地时刻，再加上估计的单向延迟：
 * 目标位置 = 收到的位置 + (本地现在 − 收到时刻) + 单向延迟（播放时），暂停时就是收到的位置。
 */
public final class TvClock
{
    /** 偏差小于这个不动 */
    public static final long TOLERANCE_MS = 300L;
    /** 偏差大于这个直接跳转（重开读取点） */
    public static final long SEEK_THRESHOLD_MS = 1_500L;
    /** 单向延迟估计的上限（ping 一半，太大说明网络不正常，不全信） */
    public static final long MAX_LATENCY_MS = 1_000L;

    public enum Action
    {
        /** 在容差内，不动 */
        NONE,
        /** 稍有偏差：画面丢帧或重复帧、声音丢样本或补静音追上 */
        ADJUST,
        /** 偏差大：跳转 */
        SEEK
    }

    private final long positionMs;
    private final boolean playing;
    private final long receivedAtMs;
    private final long latencyMs;

    public TvClock(long positionMs, boolean playing, long receivedAtMs, long latencyMs)
    {
        this.positionMs = Math.max(0, positionMs);
        this.playing = playing;
        this.receivedAtMs = receivedAtMs;
        this.latencyMs = Math.max(0, Math.min(MAX_LATENCY_MS, latencyMs));
    }

    public boolean isPlaying()
    {
        return playing;
    }

    /** 收到这份状态的本地时刻（状态变了就是新的值） */
    public long getReceivedAtMs()
    {
        return receivedAtMs;
    }

    /** 本地时刻 nowMs 时应当播到哪里（毫秒） */
    public long targetAt(long nowMs)
    {
        if(!playing)
            return positionMs;
        return positionMs + latencyMs + Math.max(0, nowMs - receivedAtMs);
    }

    /** 视频长 durationMs（不知道时传 0 或负数）：到 nowMs 时是否已经播完 */
    public boolean endedAt(long nowMs, long durationMs)
    {
        return durationMs > 0 && targetAt(nowMs) >= durationMs;
    }

    /** 本地播到 localMs、目标是 targetMs 时该怎么做 */
    public static Action decide(long localMs, long targetMs)
    {
        long diff = Math.abs(localMs - targetMs);
        if(diff > SEEK_THRESHOLD_MS)
            return Action.SEEK;
        if(diff > TOLERANCE_MS)
            return Action.ADJUST;
        return Action.NONE;
    }

    /** 单向延迟估计：连接 ping 的一半（ping 未知、负数时按 0） */
    public static long latencyFromPing(int pingMs)
    {
        return pingMs <= 0 ? 0 : pingMs / 2;
    }
}
