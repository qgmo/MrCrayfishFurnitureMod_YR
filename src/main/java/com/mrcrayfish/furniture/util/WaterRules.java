package com.mrcrayfish.furniture.util;

/**
 * 用水家具的数值（纯逻辑，悠然服务器魔改，服主 11-14 定，Minecraft 尺度）。单位 mB。
 * <pre>
 * 家具   容量     消耗 / 补水
 * 盆池   1000     开着时每 tick 抽 50
 * 水槽   1000     同上
 * 浴缸   16000    每 tick 抽 100（原来 16 格水位，按 1000 mB 一格换算显示）
 * 马桶   不存水   冲一次 100
 * 花洒   不存水   开着每 tick 1
 * 水龙头 不存水   放一格抽 1000
 * </pre>
 * 盆池、水槽、浴缸里的水每个游戏日（按区块加载时间）蒸发容量的 1/3。
 */
public final class WaterRules
{
    public static final int BASIN_CAPACITY = 1000;
    public static final int BASIN_FILL_PER_TICK = 50;
    public static final int BATH_CAPACITY = 16000;
    public static final int BATH_FILL_PER_TICK = 100;
    public static final int BATH_LEVELS = 16;
    public static final int TOILET_FLUSH = 100;
    public static final int SHOWER_PER_TICK = 1;
    public static final int TAP_PER_BLOCK = 1000;
    public static final int BUCKET = 1000;

    /** 一个游戏日多少 tick */
    public static final int DAY_TICKS = 24000;
    /** 每天蒸发容量的几分之一 */
    public static final int EVAPORATE_FRACTION = 3;

    private WaterRules() {}

    /** 浴缸水位（0..16）：有水就至少 1 格，按 1000 mB 一格向上取整 */
    public static int bathLevel(int amount)
    {
        if(amount <= 0)
            return 0;
        int perLevel = BATH_CAPACITY / BATH_LEVELS;
        return Math.min(BATH_LEVELS, (amount + perLevel - 1) / perLevel);
    }

    /** 旧存档的浴缸水位换成 mB */
    public static int bathAmountFromLevel(int level)
    {
        int perLevel = BATH_CAPACITY / BATH_LEVELS;
        return Math.max(0, Math.min(BATH_LEVELS, level)) * perLevel;
    }

    /**
     * 蒸发：每 tick 调一次 {@link #tick}，累计到 1 mB 就返回 1。按“容量 / 3 / 一天”的速度，余数留到下一 tick（随存档）。
     */
    public static final class Evaporation
    {
        private final long perMbUnits;
        private final int capacity;
        private long accumulated;

        public Evaporation(int capacity)
        {
            this.capacity = Math.max(1, capacity);
            this.perMbUnits = (long) DAY_TICKS * EVAPORATE_FRACTION;
        }

        /** 过了一 tick：返回这一 tick 要蒸发掉的 mB（没水时不累计） */
        public int tick(int amount)
        {
            if(amount <= 0)
            {
                accumulated = 0;
                return 0;
            }
            accumulated += capacity;
            int mb = (int) (accumulated / perMbUnits);
            accumulated -= (long) mb * perMbUnits;
            return Math.min(mb, amount);
        }

        public long getAccumulated()
        {
            return accumulated;
        }

        public void setAccumulated(long accumulated)
        {
            this.accumulated = accumulated >= 0 && accumulated < perMbUnits ? accumulated : 0;
        }
    }
}
