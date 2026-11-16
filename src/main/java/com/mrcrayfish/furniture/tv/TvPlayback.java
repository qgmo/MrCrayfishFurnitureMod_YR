package com.mrcrayfish.furniture.tv;

/**
 * 一台电视的播放进度（纯逻辑，悠然服务器魔改）。服务端只存“播放 / 暂停、基准位置、基准时刻”，
 * 当前位置 = 暂停时的基准位置，或播放时的基准位置 + (现在 − 基准时刻)。时刻一律用服务端的现实时间（毫秒），
 * 不用游戏刻：游戏卡顿时刻会变慢，视频却按现实时间走。视频多长由在场玩家的客户端报上来（{@link TvDurations}）：
 * 采信时播到头停在片尾、暂停（{@link #stopAtEnd}）；不采信时服务端不管片尾，后退时按客户端给的时长先夹到片尾以内（{@link #rewind}）。
 */
public final class TvPlayback
{
    /** 前进、后退一次跳多少（服主 11-14：10 秒） */
    public static final long SEEK_STEP_MS = 10_000L;
    /** 进度条显示多久 */
    public static final long OSD_MS = 3_000L;
    /** 位置上限（约 100 天），防止数字溢出 */
    public static final long MAX_POSITION_MS = 100L * 24 * 3600 * 1000;

    private boolean playing = true;
    private long basePosMs;
    private long baseTimeMs;
    /** 进度条显示到什么时刻（服务端时间），0 表示不显示 */
    private long osdUntilMs;

    public TvPlayback() {}

    public TvPlayback(boolean playing, long basePosMs, long baseTimeMs)
    {
        this.playing = playing;
        this.basePosMs = clamp(basePosMs);
        this.baseTimeMs = baseTimeMs;
    }

    public boolean isPlaying()
    {
        return playing;
    }

    public long getBasePosMs()
    {
        return basePosMs;
    }

    public long getBaseTimeMs()
    {
        return baseTimeMs;
    }

    /** 当前位置（毫秒）。服务端时钟往回调过时不倒退 */
    public long positionAt(long nowMs)
    {
        if(!playing)
            return basePosMs;
        return clamp(basePosMs + Math.max(0, nowMs - baseTimeMs));
    }

    /** 换台、开机：从头播 */
    public void restart(long nowMs)
    {
        playing = true;
        basePosMs = 0;
        baseTimeMs = nowMs;
    }

    /** 播完时按“播放”从头播的容差：进度离片尾不到这么多也算播完 */
    public static final long END_SLACK_MS = 1_000L;

    /**
     * 遥控器的“播放 / 暂停”（服主 11-16）：已经播到片尾（endMs &gt; 0 且进度到了片尾前 {@value #END_SLACK_MS} ms 以内）就从头播，
     * 否则照常播放 / 暂停。返回是不是从头播了。
     */
    public boolean playOrRestart(long nowMs, long endMs)
    {
        if(endMs > 0 && positionAt(nowMs) >= endMs - END_SLACK_MS)
        {
            restart(nowMs);
            return true;
        }
        togglePlay(nowMs);
        return false;
    }

    /** 播放 / 暂停 */
    public void togglePlay(long nowMs)
    {
        long pos = positionAt(nowMs);
        playing = !playing;
        basePosMs = pos;
        baseTimeMs = nowMs;
    }

    /** 前进（正）或后退（负），下限 0；播放 / 暂停状态不变 */
    public void seek(long nowMs, long deltaMs)
    {
        basePosMs = clamp(positionAt(nowMs) + deltaMs);
        baseTimeMs = nowMs;
    }

    /**
     * 后退 stepMs。endMs &gt; 0 时先把当前位置夹到 endMs 以内（播完很久以后后退，从片尾往回算）；播放 / 暂停状态不变。
     */
    public void rewind(long nowMs, long stepMs, long endMs)
    {
        long pos = positionAt(nowMs);
        if(endMs > 0 && pos > endMs)
            pos = endMs;
        basePosMs = clamp(pos - Math.max(0, stepMs));
        baseTimeMs = nowMs;
    }

    /**
     * 到了片尾（endMs）就停在片尾、暂停。返回有没有改动（要同步给客户端）。
     */
    public boolean stopAtEnd(long nowMs, long endMs)
    {
        if(endMs <= 0 || positionAt(nowMs) < endMs)
            return false;
        if(!playing && basePosMs == endMs)
            return false;
        playing = false;
        basePosMs = clamp(endMs);
        baseTimeMs = nowMs;
        return true;
    }

    /** 弹出进度条；已经显示着时再按一次就收起 */
    public void toggleOsd(long nowMs)
    {
        osdUntilMs = isOsdShown(nowMs) ? 0 : nowMs + OSD_MS;
    }

    /** 操作（快进、后退、音量、弹幕开关）时显示一会儿进度条 */
    public void showOsd(long nowMs)
    {
        osdUntilMs = nowMs + OSD_MS;
    }

    public boolean isOsdShown(long nowMs)
    {
        return osdUntilMs > nowMs;
    }

    /** 进度条还要显示多久（毫秒，发给客户端用） */
    public long osdLeftMs(long nowMs)
    {
        return Math.max(0, osdUntilMs - nowMs);
    }

    private static long clamp(long pos)
    {
        return Math.max(0, Math.min(MAX_POSITION_MS, pos));
    }
}
