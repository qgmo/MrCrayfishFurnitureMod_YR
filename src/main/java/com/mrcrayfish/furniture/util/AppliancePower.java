package com.mrcrayfish.furniture.util;

/**
 * 电器的耗电（纯逻辑，悠然服务器魔改，服主 11-14 定）。单位 FE/tick，只在工作时耗电，待机不耗。
 * 每样电器自带够用约 {@value #BUFFER_SECONDS} 秒的小缓存（按最大耗电算）。
 */
public final class AppliancePower
{
    public static final int BUFFER_SECONDS = 20;
    public static final int BUFFER_TICKS = BUFFER_SECONDS * 20;

    /** 电视放 GIF */
    public static final int TV_GIF = 2;
    /** 电视放视频（下一轮的 bilibili 播放器用；缓存按它算） */
    public static final int TV_VIDEO = 4;
    /** 电脑开机（有人开着界面） */
    public static final int COMPUTER = 4;
    /** 打印机打印时 */
    public static final int PRINTER = 8;
    /** 烤面包机烤时 */
    public static final int TOASTER = 8;
    /** 微波炉加热时 */
    public static final int MICROWAVE = 12;
    /** 洗衣机洗时 */
    public static final int WASHING_MACHINE = 6;
    /** 洗碗机洗时（照洗衣机，服主 11-14） */
    public static final int DISHWASHER = 6;
    /** 吊扇转时 */
    public static final int CEILING_FAN = 1;
    /** 烤箱烤时 */
    public static final int OVEN = 12;
    /** 冰箱（冷冻柜 + 冷藏室一台）一直耗 */
    public static final int FRIDGE = 1;
    /** 冷冻时再加 */
    public static final int FREEZING = 8;

    private AppliancePower() {}

    /** 按最大耗电算的缓存大小 */
    public static int bufferFor(int maxPerTick)
    {
        return Math.max(1, maxPerTick) * BUFFER_TICKS;
    }

    /** 接线送进来能收多少：不超过剩下的空间 */
    public static int receivable(int stored, int capacity, int offered)
    {
        if(offered <= 0)
            return 0;
        return Math.max(0, Math.min(offered, capacity - stored));
    }

    /**
     * 付这一 tick 的电：先用缓存（接线送进来的电），缓存不够时差多少由卡的网络补。
     * 返回网络要补的量（0 表示缓存够）；调用方从网络取到这么多才算付成功，取不到就整笔不付。
     */
    public static int shortfall(int stored, int need)
    {
        return need <= stored ? 0 : need - Math.max(0, stored);
    }
}
