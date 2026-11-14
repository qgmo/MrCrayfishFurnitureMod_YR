package com.mrcrayfish.furniture.util;

/**
 * 电栅栏的电（纯逻辑，悠然服务器魔改）。
 * <ul>
 * <li>容量由合成用的电池决定，没有记录时（旧存档、创造模式物品栏里拿的）按 {@link #DEFAULT_CAPACITY}，电量按 0；</li>
 * <li>每面每 tick 最多传 {@link #TRANSFER_PER_FACE} FE（同沉浸工程低压线），不损耗；</li>
 * <li>电一次耗 {@link #ZAP_COST} FE，不够就不电；</li>
 * <li>相邻电栅栏之间按“满的比例”拉平：多的那边往少的那边送，送到两边比例相同为止。</li>
 * </ul>
 */
public final class FencePower
{
    /** 没有记录容量时按 SCP 普通电池 / IC2 RE 电池的 2 万 FE */
    public static final int DEFAULT_CAPACITY = 20000;

    /** 每面每 tick 最多传多少 FE */
    public static final int TRANSFER_PER_FACE = 256;

    /** 电一次耗多少 FE */
    public static final int ZAP_COST = 50;

    private FencePower() {}

    /** 读存档 / 物品里的容量：不是正数按默认 */
    public static int sanitizeCapacity(int capacity)
    {
        return capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }

    /** 读存档 / 物品里的电量：夹在 [0, capacity] */
    public static int sanitizeEnergy(int energy, int capacity)
    {
        return Math.max(0, Math.min(energy, capacity));
    }

    /**
     * 从 A 往 B 送多少电才能让两边满的比例相同（只算 A 比 B 满的情况，否则 0）。
     * 结果不超过 max、A 现有的电、B 剩下的空间；向下取整，所以不会来回送。
     */
    public static int equalizeTransfer(int energyA, int capacityA, int energyB, int capacityB, int max)
    {
        if(capacityA <= 0 || capacityB <= 0 || max <= 0)
            return 0;
        long diff = (long) energyA * capacityB - (long) energyB * capacityA;
        if(diff <= 0)
            return 0;
        long amount = diff / ((long) capacityA + capacityB);
        amount = Math.min(amount, max);
        amount = Math.min(amount, energyA);
        amount = Math.min(amount, (long) capacityB - energyB);
        return amount > 0 ? (int) amount : 0;
    }

    /** 能收多少：不超过每面上限与剩下的空间 */
    public static int receivable(int energy, int capacity, int offered)
    {
        if(offered <= 0)
            return 0;
        return Math.max(0, Math.min(Math.min(offered, TRANSFER_PER_FACE), capacity - energy));
    }
}
