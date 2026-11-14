package com.mrcrayfish.furniture.water;

import com.mrcrayfish.furniture.compat.WaterFluids;
import com.mrcrayfish.furniture.util.WaterRules;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;

/**
 * 盆池、水槽、浴缸自己的水（悠然服务器魔改，服主 11-14 定）：
 * <ul>
 * <li>只装配置里认的水（{@link WaterFluids}），一次只装一种；</li>
 * <li>开着时每 tick 从下面的 Forge 流体容器抽水，满了自动关；</li>
 * <li>水随时间蒸发（每个游戏日容量的 1/3，按区块加载时间）；</li>
 * <li><b>不对外提供流体能力</b>：管道、泵抽不走也灌不进；这个 {@link IFluidHandler} 只给玩家拿桶舀水、倒水用
 * （{@link FluidUtil#interactWithFluidHandler}，桶要整 1000 mB 才舀得起、倒得进）。</li>
 * </ul>
 * 存档在方块实体 NBT 的 {@value #TAG} 里；旧存档没有时按空、关着处理。
 */
public class FurnitureTank implements IFluidHandler
{
    public static final String TAG = "WaterTank";

    private final int capacity;
    private final int fillPerTick;
    private final WaterRules.Evaporation evaporation;
    private final Runnable onChanged;

    @Nullable
    private FluidStack fluid;
    private boolean open;

    public FurnitureTank(int capacity, int fillPerTick, Runnable onChanged)
    {
        this.capacity = capacity;
        this.fillPerTick = fillPerTick;
        this.evaporation = new WaterRules.Evaporation(capacity);
        this.onChanged = onChanged;
    }

    public int getCapacity()
    {
        return capacity;
    }

    public int getAmount()
    {
        return fluid == null ? 0 : fluid.amount;
    }

    @Nullable
    public FluidStack getFluid()
    {
        return fluid == null ? null : fluid.copy();
    }

    public boolean hasWater()
    {
        return getAmount() > 0;
    }

    public boolean isFull()
    {
        return getAmount() >= capacity;
    }

    public boolean isOpen()
    {
        return open;
    }

    public void setOpen(boolean open)
    {
        if(this.open != open)
        {
            this.open = open;
            onChanged.run();
        }
    }

    private void setAmount(int amount)
    {
        if(fluid == null)
            return;
        fluid.amount = amount;
        if(fluid.amount <= 0)
            fluid = null;
        onChanged.run();
    }

    /**
     * 过一 tick（服务端）：先蒸发；开着时从 sources 里依次抽水（每 tick 最多 fillPerTick），满了自动关。
     *
     * @param world   世界
     * @param sources 从哪些格抽（这一格、和接触它的那一面）
     */
    public void tick(World world, BlockPos[] sources, EnumFacing[] sides)
    {
        int evaporated = evaporation.tick(getAmount());
        if(evaporated > 0)
        {
            setAmount(getAmount() - evaporated);
        }
        if(!open)
            return;
        if(isFull())
        {
            setOpen(false);
            return;
        }
        int room = Math.min(fillPerTick, capacity - getAmount());
        for(int i = 0; i < sources.length && room > 0; i++)
        {
            if(!world.isBlockLoaded(sources[i]))
                continue;
            IFluidHandler handler = FluidUtil.getFluidHandler(world, sources[i], sides[i]);
            if(handler == null)
                continue;
            FluidStack got = WaterFluids.drainWater(handler, fluid, room, true);
            if(got != null && got.amount > 0)
            {
                int add = Math.min(got.amount, room);
                if(fluid == null)
                {
                    fluid = new FluidStack(got, 0);
                }
                fluid.amount += add;
                room -= add;
                onChanged.run();
            }
        }
        if(isFull())
        {
            setOpen(false);
        }
    }

    /** 旧存档的浴缸水位换算（原版水） */
    public void setLegacyWater(int amount)
    {
        FluidStack water = new FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, Math.max(0, Math.min(capacity, amount)));
        this.fluid = water.amount > 0 ? water : null;
    }

    @Override
    public IFluidTankProperties[] getTankProperties()
    {
        return new IFluidTankProperties[]{new FluidTankProperties(getFluid(), capacity, true, true)};
    }

    @Override
    public int fill(FluidStack resource, boolean doFill)
    {
        if(!WaterFluids.isWater(resource))
            return 0;
        if(fluid != null && !fluid.isFluidEqual(resource))
            return 0;
        int amount = Math.min(resource.amount, capacity - getAmount());
        if(amount <= 0)
            return 0;
        if(doFill)
        {
            if(fluid == null)
            {
                fluid = new FluidStack(resource, 0);
            }
            fluid.amount += amount;
            onChanged.run();
        }
        return amount;
    }

    @Nullable
    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain)
    {
        if(resource == null || fluid == null || !fluid.isFluidEqual(resource))
            return null;
        return drain(resource.amount, doDrain);
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain)
    {
        if(fluid == null || maxDrain <= 0)
            return null;
        int amount = Math.min(maxDrain, fluid.amount);
        FluidStack out = new FluidStack(fluid, amount);
        if(doDrain)
        {
            setAmount(fluid.amount - amount);
        }
        return out;
    }

    public void readFromNBT(NBTTagCompound parent)
    {
        NBTTagCompound tag = parent.getCompoundTag(TAG);
        FluidStack read = tag.hasKey("Fluid") ? FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("Fluid")) : null;
        if(read != null && read.amount > 0)
        {
            read.amount = Math.min(read.amount, capacity);
            this.fluid = read;
        }
        else
        {
            this.fluid = null;
        }
        this.open = tag.getBoolean("Open");
        this.evaporation.setAccumulated(tag.getLong("Evaporation"));
    }

    public void writeToNBT(NBTTagCompound parent)
    {
        NBTTagCompound tag = new NBTTagCompound();
        if(fluid != null)
        {
            tag.setTag("Fluid", fluid.writeToNBT(new NBTTagCompound()));
        }
        tag.setBoolean("Open", open);
        tag.setLong("Evaporation", evaporation.getAccumulated());
        parent.setTag(TAG, tag);
    }

    public static boolean hasSaved(NBTTagCompound parent)
    {
        return parent.hasKey(TAG);
    }
}
