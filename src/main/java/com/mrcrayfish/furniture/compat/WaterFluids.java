package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.handler.ConfigurationHandler;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * 用水家具认哪些水（悠然服务器魔改）：配置 {@code water-fluids} 里列的流体名（Forge 流体注册名），默认原版水、
 * 悠然模组的矿泉水（fuild_mineral_water）、意志坚定的纯净水（purified_water）。没装对应模组的名字自动忽略。
 */
public final class WaterFluids
{
    public static final String[] DEFAULTS = {"water", "fuild_mineral_water", "purified_water"};

    private WaterFluids() {}

    public static boolean isWater(@Nullable Fluid fluid)
    {
        if(fluid == null)
            return false;
        String name = FluidRegistry.getFluidName(fluid);
        if(name == null)
            return false;
        String[] list = ConfigurationHandler.waterFluids;
        return Arrays.asList(list == null ? DEFAULTS : list).contains(name);
    }

    public static boolean isWater(@Nullable FluidStack stack)
    {
        return stack != null && stack.amount > 0 && isWater(stack.getFluid());
    }

    /**
     * 从容器里取水：want 为 null 时取任意一种认得的水，否则只取同一种（家具里已经有水时不混）。
     * 返回取到（或模拟时能取到）的水；取不到返回 null。
     */
    @Nullable
    public static FluidStack drainWater(IFluidHandler handler, @Nullable FluidStack want, int max, boolean doDrain)
    {
        if(max <= 0)
            return null;
        if(want != null)
        {
            FluidStack got = handler.drain(new FluidStack(want, max), doDrain);
            return isWater(got) && got.isFluidEqual(want) ? got : null;
        }
        IFluidTankProperties[] tanks = handler.getTankProperties();
        if(tanks == null)
            return null;
        for(IFluidTankProperties tank : tanks)
        {
            FluidStack contents = tank.getContents();
            if(isWater(contents) && tank.canDrain())
            {
                FluidStack got = handler.drain(new FluidStack(contents, max), doDrain);
                if(isWater(got) && got.isFluidEqual(contents))
                    return got;
            }
        }
        return null;
    }

    /** 恰好取 amount（够了才取，不够一点也不取） */
    @Nullable
    public static FluidStack drainExactly(IFluidHandler handler, @Nullable FluidStack want, int amount)
    {
        FluidStack sim = drainWater(handler, want, amount, false);
        if(sim == null || sim.amount < amount)
            return null;
        FluidStack got = drainWater(handler, sim, amount, true);
        return got != null && got.amount >= amount ? got : null;
    }
}
