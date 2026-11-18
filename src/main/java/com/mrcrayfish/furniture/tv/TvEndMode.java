package com.mrcrayfish.furniture.tv;

import javax.annotation.Nullable;

/**
 * 视频播完以后做什么（纯逻辑，悠然服务器魔改，服主 11-18）。存在服务端的电视里，对所有看这台电视的人一起生效。
 * <ul>
 * <li>{@link #STOP}：停在片尾（原来的行为，默认）；</li>
 * <li>{@link #REPEAT}：这一台从头再播；</li>
 * <li>{@link #NEXT}：换到下一个填了内容的频道，最后一台播完回到第一台；只有一台有内容时等同重播。</li>
 * </ul>
 * 只对“服务端采信了时长”的视频起作用（{@link TvDurations}）：没采信时长时照原来的做法（不停）。
 * GIF、直播没有片尾，三档对它们都不起作用（服主 11-18：直播下播不算播完）；“下一台”换到 GIF 或直播就停在那一台。
 */
public enum TvEndMode
{
    STOP("cfm.tv.settings.end_stop"),
    REPEAT("cfm.tv.settings.end_repeat"),
    NEXT("cfm.tv.settings.end_next");

    private static final TvEndMode[] VALUES = values();

    /** 设置界面按钮上的语言键 */
    public final String langKey;

    TvEndMode(String langKey)
    {
        this.langKey = langKey;
    }

    @Nullable
    public static TvEndMode byId(int id)
    {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    /** 存档里读出来的（不认得的按默认的“停住”） */
    public static TvEndMode fromNbt(int id)
    {
        TvEndMode m = byId(id);
        return m == null ? STOP : m;
    }

    /** 设置界面的按钮：点一下换一档 */
    public TvEndMode cycle()
    {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /** 播完时的结果 */
    public enum Outcome
    {
        /** 停在片尾、暂停 */
        STOP,
        /** 这一台从头播 */
        RESTART,
        /** 换到 {@link #nextChannel} 那一台、从头播 */
        SWITCH
    }

    /** 共 count 个频道、当前是第 current 个时，这一档播完的结果 */
    public Outcome outcome(int current, int count)
    {
        switch(this)
        {
            case REPEAT:
                return Outcome.RESTART;
            case NEXT:
                return nextChannel(current, count) == current ? Outcome.RESTART : Outcome.SWITCH;
            default:
                return Outcome.STOP;
        }
    }

    /**
     * 下一个频道的编号。电视里存的频道已经去掉了空的（保存时只留填了内容的），所以就是下一个，最后一个回到第一个；
     * 只有一个（或编号不对）时还是自己。
     */
    public static int nextChannel(int current, int count)
    {
        if(count <= 1)
            return Math.max(0, Math.min(current, Math.max(0, count - 1)));
        return (Math.max(0, current) + 1) % count;
    }
}
