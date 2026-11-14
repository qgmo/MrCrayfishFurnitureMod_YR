package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.util.ClockPower;
import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import net.minecraft.item.ItemStack;

/**
 * 工业时代 2 的电池（悠然服务器魔改）：用 IC2 的电器管理器取电，1 EU = 2 FE。
 * <p>
 * <b>引用了 IC2 的类，只能在装了 IC2 时加载</b>：调用前先 {@code Loader.isModLoaded("ic2")}（见 {@link ItemBatteries}）。
 */
final class Ic2Batteries
{
    private Ic2Batteries() {}

    /** 能放电的电器（电池、水晶等；电钻之类只耗电不放电的不算） */
    static boolean isBattery(ItemStack stack)
    {
        return stack.getItem() instanceof IElectricItem
                && ((IElectricItem) stack.getItem()).canProvideEnergy(stack)
                && ElectricItem.manager.getMaxCharge(stack) > 0;
    }

    static int chargeFe(ItemStack stack)
    {
        return ClockPower.euToFe(ElectricItem.manager.getCharge(stack));
    }

    static int capacityFe(ItemStack stack)
    {
        return ClockPower.euToFe(ElectricItem.manager.getMaxCharge(stack));
    }

    static boolean hasCharge(ItemStack stack, int fe)
    {
        return chargeFe(stack) >= fe;
    }

    /** 取 fe 点电，够了才算成功；不够时不取（先试放，够再真放） */
    static boolean drain(ItemStack stack, int fe)
    {
        double eu = ClockPower.feToEu(fe);
        if(ElectricItem.manager.discharge(stack, eu, Integer.MAX_VALUE, true, true, true) < eu)
            return false;
        return ElectricItem.manager.discharge(stack, eu, Integer.MAX_VALUE, true, true, false) >= eu;
    }
}
