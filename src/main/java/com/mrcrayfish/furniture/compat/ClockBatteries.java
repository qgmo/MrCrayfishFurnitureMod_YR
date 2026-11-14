package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.util.ClockPower;
import net.minecraft.item.ItemStack;

/**
 * 数字时钟认电池、取电（悠然服务器魔改），见 {@link ItemBatteries}。
 * 所有方法都就地改传进来的物品（电池放电后，物品自己带着新电量）。
 */
public final class ClockBatteries
{
    private ClockBatteries() {}

    /** 能当电池：对外放得出 Forge Energy，或是 IC2 的能放电的电器（电量空了也算） */
    public static boolean isBattery(ItemStack stack)
    {
        return ItemBatteries.isBattery(stack);
    }

    /** 还有至少够耗一次的电 */
    public static boolean hasCharge(ItemStack stack)
    {
        return ItemBatteries.hasCharge(stack, ClockPower.FE_PER_DRAIN);
    }

    /** 耗一次电（{@link ClockPower#FE_PER_DRAIN} FE）；成功返回 true，电池里没有这么多电则不取、返回 false */
    public static boolean drain(ItemStack stack)
    {
        return ItemBatteries.drain(stack, ClockPower.FE_PER_DRAIN);
    }
}
