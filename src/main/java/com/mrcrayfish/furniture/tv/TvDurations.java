package com.mrcrayfish.furniture.tv;

import java.util.HashMap;
import java.util.Map;

/**
 * 一台电视当前视频的时长（纯逻辑，悠然服务器魔改，服主 11-14）。服务端不碰视频，时长由在场玩家的客户端取到视频后报上来：
 * <ul>
 * <li>每份报告先核对范围：大于 0、不超过 {@value #MAX_MS} 毫秒（24 小时）；</li>
 * <li>每个玩家只算一份（再报就换掉自己那份），最多记 {@value #MAX_REPORTERS} 个人；</li>
 * <li>只有一个人报：采信那一份；几个人报的最长与最短相差不超过 {@value #TOLERANCE_MS} 毫秒和最长的 1% 两者中较大的那个：
 * 采信最长的那份（不把谁的视频截短）；相差更大：不采信，服务端不管片尾（客户端后退时自己把进度夹在片尾以内）；</li>
 * <li>换台、开机、改了当前频道：清掉（{@link #clear}），报告的编号（{@link #epoch}）跟着变，旧编号的报告不收。</li>
 * </ul>
 *
 * @param <K> 玩家标识
 */
public final class TvDurations<K>
{
    /** 时长上限：24 小时 */
    public static final long MAX_MS = 24L * 3600 * 1000;
    /** 几个人报的时长相差多少以内算一致：2 秒，或最长那份的 1%，取大的 */
    public static final long TOLERANCE_MS = 2_000L;
    public static final int TOLERANCE_PERCENT = 1;
    public static final int MAX_REPORTERS = 32;

    private final Map<K, Long> reports = new HashMap<>();
    private int epoch;

    public TvDurations(int startEpoch)
    {
        this.epoch = startEpoch;
    }

    public static boolean valid(long ms)
    {
        return ms > 0 && ms <= MAX_MS;
    }

    /** 两份时长算不算一致 */
    public static boolean agree(long a, long b)
    {
        long max = Math.max(a, b), min = Math.min(a, b);
        return max - min <= Math.max(TOLERANCE_MS, max * TOLERANCE_PERCENT / 100);
    }

    /** 现在的报告编号（发给客户端，报告时带回来） */
    public int epoch()
    {
        return epoch;
    }

    /** 换台等：清掉所有报告，编号换一个 */
    public void clear()
    {
        reports.clear();
        epoch++;
    }

    /**
     * 收一份报告。不在范围内、人数满了（且不是已报过的人）时不收，返回 false。
     */
    public boolean report(K who, long ms)
    {
        if(!valid(ms))
            return false;
        if(!reports.containsKey(who) && reports.size() >= MAX_REPORTERS)
            return false;
        reports.put(who, ms);
        return true;
    }

    public int count()
    {
        return reports.size();
    }

    /** 有人报了，但报的不一致（不采信） */
    public boolean disagree()
    {
        if(reports.size() < 2)
            return false;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for(long v : reports.values())
        {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return !agree(min, max);
    }

    /** 采信的时长（毫秒）；没人报或不一致时为 0 */
    public long trusted()
    {
        if(reports.isEmpty() || disagree())
            return 0;
        long max = 0;
        for(long v : reports.values())
            max = Math.max(max, v);
        return max;
    }
}
