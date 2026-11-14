package com.mrcrayfish.furniture.power;

import com.mrcrayfish.furniture.compat.RfToolsCards;
import com.mrcrayfish.furniture.util.AppliancePower;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * 一台电器的电（悠然服务器魔改，服主 11-14 定）：
 * <ul>
 * <li>小缓存：够最大耗电用约 20 秒，只收接线送进来的 Forge Energy，只从指定的面收（{@link #inputFaces}），不能被抽走；</li>
 * <li>能量单元卡：插上 RFTools 的能量单元卡后作为该网络的用电户取电；</li>
 * <li>付电：先用缓存（接线来的电优先），不够的部分从卡的网络取，取不够整笔不付；</li>
 * <li>{@link #isRunning()}：这一 tick 付上电在工作，同步给客户端（没电时画面黑、暂停等由各电器决定）。</li>
 * </ul>
 * 存档在方块实体 NBT 的 {@value #TAG} 里；旧存档没有时按没电、没卡处理。
 */
public class ApplianceEnergy
{
    public static final String TAG = "Appliance";
    /** 多久重算一次取电成本（tick） */
    private static final int FACTOR_REFRESH = 100;

    private final TileEntity owner;
    private final int capacity;
    private final Predicate<EnumFacing> inputFaces;
    private final Runnable onRunningChanged;

    private int stored;
    private ItemStack card = ItemStack.EMPTY;
    private boolean running;

    private float factor = 1.0F;
    private long factorTime = Long.MIN_VALUE;

    private final IEnergyStorage port = new Port();

    /**
     * @param maxPerTick       最大耗电（定缓存大小）
     * @param inputFaces       哪些面能接电（参数是方块的面）
     * @param onRunningChanged 工作状态变了（服务端）：要同步给客户端
     */
    public ApplianceEnergy(TileEntity owner, int maxPerTick, Predicate<EnumFacing> inputFaces, Runnable onRunningChanged)
    {
        this.owner = owner;
        this.capacity = AppliancePower.bufferFor(maxPerTick);
        this.inputFaces = inputFaces;
        this.onRunningChanged = onRunningChanged;
    }

    public int getStored()
    {
        return stored;
    }

    public int getCapacity()
    {
        return capacity;
    }

    public boolean isRunning()
    {
        return running;
    }

    public ItemStack getCard()
    {
        return card;
    }

    public boolean hasCard()
    {
        return !card.isEmpty();
    }

    /** 放卡（服务端），返回原来的卡 */
    public ItemStack setCard(ItemStack newCard)
    {
        ItemStack old = this.card;
        this.card = newCard.isEmpty() ? ItemStack.EMPTY : newCard;
        this.factorTime = Long.MIN_VALUE;
        this.dirty();
        return old;
    }

    private void dirty()
    {
        World world = owner.getWorld();
        if(world != null)
        {
            world.markChunkDirty(owner.getPos(), owner);
        }
    }

    /**
     * 付 fe 点电（服务端）：先用缓存，不够的部分从卡的网络取。付不起返回 false，什么也不扣。
     */
    public boolean draw(int fe)
    {
        World world = owner.getWorld();
        if(world == null || world.isRemote)
            return running;
        if(fe <= 0)
            return true;
        int shortfall = AppliancePower.shortfall(stored, fe);
        if(shortfall == 0)
        {
            stored -= fe;
            this.dirty();
            return true;
        }
        if(!card.isEmpty())
        {
            int got = RfToolsCards.extract(world, card, shortfall, this.factor(world));
            if(got >= shortfall)
            {
                stored = 0;
                this.dirty();
                return true;
            }
        }
        return false;
    }

    /**
     * 每 tick 调一次（服务端）：working 时付 fe 点电。返回这一 tick 能不能工作；状态变了就通知同步。
     * 客户端直接返回同步来的状态。
     */
    public boolean tick(boolean working, int fe)
    {
        World world = owner.getWorld();
        if(world == null || world.isRemote)
            return working && running;
        boolean ok = working && this.draw(fe);
        this.setRunning(ok);
        return ok;
    }

    /** 客户端：界面同步来的缓存电量（只用于显示） */
    public void setClientStored(int stored)
    {
        this.stored = Math.max(0, Math.min(capacity, stored));
    }

    /** 客户端：界面同步来的工作状态 */
    public void setClientRunning(boolean running)
    {
        this.running = running;
    }

    public void setRunning(boolean running)
    {
        if(this.running != running)
        {
            this.running = running;
            this.dirty();
            World world = owner.getWorld();
            if(world != null && !world.isRemote)
            {
                onRunningChanged.run();
            }
        }
    }

    private float factor(World world)
    {
        long now = world.getTotalWorldTime();
        if(factorTime == Long.MIN_VALUE || now - factorTime >= FACTOR_REFRESH || now < factorTime)
        {
            factor = RfToolsCards.costFactor(world, owner.getPos(), card);
            factorTime = now;
        }
        return factor;
    }

    public boolean isInputFace(@Nullable EnumFacing facing)
    {
        return facing != null && inputFaces.test(facing);
    }

    /** 某一面的 Forge Energy 接口（只在进电面给） */
    @Nullable
    public IEnergyStorage getPort(@Nullable EnumFacing facing)
    {
        return isInputFace(facing) ? port : null;
    }

    /** 不看面的接口（冰箱上半截从背面转给下半截用） */
    public IEnergyStorage port()
    {
        return port;
    }

    public void readFromNBT(NBTTagCompound parent)
    {
        NBTTagCompound tag = parent.getCompoundTag(TAG);
        this.stored = Math.max(0, Math.min(capacity, tag.getInteger("Stored")));
        this.card = tag.hasKey("Card", Constants.NBT.TAG_COMPOUND) ? new ItemStack(tag.getCompoundTag("Card")) : ItemStack.EMPTY;
        this.running = tag.getBoolean("Running");
        this.factorTime = Long.MIN_VALUE;
    }

    public void writeToNBT(NBTTagCompound parent)
    {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Stored", stored);
        if(!card.isEmpty())
        {
            tag.setTag("Card", card.writeToNBT(new NBTTagCompound()));
        }
        tag.setBoolean("Running", running);
        parent.setTag(TAG, tag);
    }

    private class Port implements IEnergyStorage
    {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate)
        {
            World world = owner.getWorld();
            if(world == null || world.isRemote)
                return 0;
            int received = AppliancePower.receivable(stored, capacity, maxReceive);
            if(!simulate && received > 0)
            {
                stored += received;
                ApplianceEnergy.this.dirty();
            }
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate)
        {
            return 0;
        }

        @Override
        public int getEnergyStored()
        {
            return stored;
        }

        @Override
        public int getMaxEnergyStored()
        {
            return capacity;
        }

        @Override
        public boolean canExtract()
        {
            return false;
        }

        @Override
        public boolean canReceive()
        {
            return true;
        }
    }
}
