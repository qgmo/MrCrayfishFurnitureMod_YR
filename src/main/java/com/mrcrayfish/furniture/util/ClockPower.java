package com.mrcrayfish.furniture.util;

/**
 * 数字时钟的耗电规则（纯逻辑，悠然服务器魔改）：每 {@link #DEFAULT_INTERVAL} tick（2 分钟）耗 1 FE；
 * IC2 的电按 1 EU = 2 FE 换算。
 */
public final class ClockPower
{
    /** 每次耗 1 FE 的间隔（tick）：2 分钟 */
    public static final int DEFAULT_INTERVAL = 2400;

    /** 每次耗多少 FE */
    public static final int FE_PER_DRAIN = 1;

    /** 1 EU 合多少 FE */
    public static final int FE_PER_EU = 2;

    private ClockPower() {}

    /**
     * 读耗电间隔（tick）。只给测试调快用（系统属性 cfm.clockDrainTicks），没设、不是正整数时用默认值。
     */
    public static int parseInterval(String value)
    {
        if(value == null)
            return DEFAULT_INTERVAL;
        try
        {
            int n = Integer.parseInt(value.trim());
            return n > 0 ? n : DEFAULT_INTERVAL;
        }
        catch(NumberFormatException e)
        {
            return DEFAULT_INTERVAL;
        }
    }

    public static double feToEu(int fe)
    {
        return fe / (double) FE_PER_EU;
    }

    /**
     * EU 换成 FE，向下取整（留一点浮点误差）；负数、NaN 按 0。
     */
    public static int euToFe(double eu)
    {
        if(!(eu > 0))
            return 0;
        double fe = Math.floor(eu * FE_PER_EU + 1e-6);
        return fe >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) fe;
    }

    /**
     * 耗电倒计时：每 tick 调一次 {@link #tick()}，数到 0 时返回 true（该耗一次电了）并重新开始。
     */
    public static final class DrainTimer
    {
        private final int interval;
        private int remaining;

        public DrainTimer(int interval)
        {
            this.interval = Math.max(1, interval);
            this.remaining = this.interval;
        }

        public boolean tick()
        {
            if(--remaining <= 0)
            {
                remaining = interval;
                return true;
            }
            return false;
        }

        public void reset()
        {
            remaining = interval;
        }

        public int getRemaining()
        {
            return remaining;
        }

        /** 从存档读：夹在 [1, interval]，坏数据按重新开始 */
        public void setRemaining(int remaining)
        {
            this.remaining = remaining >= 1 && remaining <= interval ? remaining : interval;
        }
    }
}
