package com.mrcrayfish.furniture.compat;

import net.minecraft.item.ItemStack;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.common.Loader;

/**
 * 物品电池（悠然服务器魔改）：数字时钟、电视遥控器、电栅栏合成都用它认电池、读电量、取电。
 * 先看 Forge Energy（SCP 的电池等），再看 IC2 的电器（装了 IC2 才查，IC2 的类只在 {@link Ic2Batteries} 里，没装时不加载）。
 * 电量一律按 FE 算，IC2 的电按 1 EU = 2 FE 换算。取电就地改传进来的物品（电池放电后，物品自己带着新电量）。
 */
public final class ItemBatteries
{
    private ItemBatteries() {}

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

    private static boolean isIc2(ItemStack stack)
    {
        return !stack.isEmpty() && ic2() && Ic2Batteries.isBattery(stack);
    }

    /** 能当电池：对外放得出 Forge Energy，或是 IC2 的能放电的电器（电量空了也算） */
    public static boolean isBattery(ItemStack stack)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
            return true;
        return isIc2(stack);
    }

    /** 剩余电量（FE）；不是电池时 0 */
    public static int charge(ItemStack stack)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
            return Math.max(0, storage.getEnergyStored());
        return isIc2(stack) ? Ic2Batteries.chargeFe(stack) : 0;
    }

    /** 满电时的电量（FE）；不是电池时 0 */
    public static int capacity(ItemStack stack)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
            return Math.max(0, storage.getMaxEnergyStored());
        return isIc2(stack) ? Ic2Batteries.capacityFe(stack) : 0;
    }

    /** 至少还有 fe 点电 */
    public static boolean hasCharge(ItemStack stack, int fe)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
            return storage.extractEnergy(fe, true) >= fe;
        return isIc2(stack) && Ic2Batteries.hasCharge(stack, fe);
    }

    /** 取 fe 点电；电池里没有这么多电则不取、返回 false */
    public static boolean drain(ItemStack stack, int fe)
    {
        IEnergyStorage storage = fe(stack);
        if(storage != null && storage.canExtract())
        {
            if(storage.extractEnergy(fe, true) < fe)
                return false;
            return storage.extractEnergy(fe, false) >= fe;
        }
        return isIc2(stack) && Ic2Batteries.drain(stack, fe);
    }

    /** 一叠里取一个（有的电池一叠多个时不肯放电，要按单个判断） */
    public static ItemStack single(ItemStack stack)
    {
        ItemStack one = stack.copy();
        one.setCount(1);
        return one;
    }
}
