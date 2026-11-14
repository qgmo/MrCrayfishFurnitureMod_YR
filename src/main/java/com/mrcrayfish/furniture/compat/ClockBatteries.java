package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.util.ClockPower;
import net.minecraft.item.ItemStack;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.common.Loader;

/**
 * 数字时钟认电池、取电（悠然服务器魔改）：先看 Forge Energy（SCP 的电池等），再看 IC2 的电器（装了 IC2 才查，
 * IC2 的类只在 {@link Ic2Batteries} 里，没装时不加载）。
 * 所有方法都就地改传进来的物品（电池放电后，物品自己带着新电量）。
 */
public final class ClockBatteries
{
    private ClockBatteries() {}

    private static boolean ic2()
    {
        return Loader.isModLoaded("ic2");
    }

    private static IEnergyStorage fe(ItemStack stack)
    {
        if(stack.isEmpty() || !stack.hasCapability(CapabilityEnergy.ENERGY, null))
            return null;
        return stack.getCapability(CapabilityEnergy.ENERGY, null);
    }

    /** 能当电池：对外放得出 Forge Energy，或是 IC2 的能放电的电器（电量空了也算） */
    public static boolean isBattery(ItemStack stack)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
            return true;
        return !stack.isEmpty() && ic2() && Ic2Batteries.isBattery(stack);
    }

    /** 还有至少够耗一次的电 */
    public static boolean hasCharge(ItemStack stack)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
            return storage.extractEnergy(ClockPower.FE_PER_DRAIN, true) >= ClockPower.FE_PER_DRAIN;
        return !stack.isEmpty() && ic2() && Ic2Batteries.isBattery(stack) && Ic2Batteries.hasCharge(stack);
    }

    /** 耗一次电（{@link ClockPower#FE_PER_DRAIN} FE）；成功返回 true，电池里没有这么多电则不取、返回 false */
    public static boolean drain(ItemStack stack)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
        {
            if(storage.extractEnergy(ClockPower.FE_PER_DRAIN, true) < ClockPower.FE_PER_DRAIN)
                return false;
            return storage.extractEnergy(ClockPower.FE_PER_DRAIN, false) >= ClockPower.FE_PER_DRAIN;
        }
        return !stack.isEmpty() && ic2() && Ic2Batteries.isBattery(stack) && Ic2Batteries.drain(stack, ClockPower.FE_PER_DRAIN);
    }
}
