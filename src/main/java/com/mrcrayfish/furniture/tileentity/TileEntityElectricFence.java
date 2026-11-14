package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.compat.Ic2Energy;
import com.mrcrayfish.furniture.util.FencePower;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;

/**
 * 电栅栏（悠然服务器魔改）：自身存电的储能块。
 * <ul>
 * <li>容量由合成用的电池决定（物品 NBT {@value #TAG_CAPACITY}、{@value #TAG_ENERGY}，放下时带进来，拆下时带走）；</li>
 * <li>任何一面都能收 Forge Energy（如沉浸工程的接线器），装了 IC2 时也收 EU（1 EU = 2 FE）；</li>
 * <li>每 tick 主动往六面送电，每面最多 {@value FencePower#TRANSFER_PER_FACE} FE、不损耗：相邻电栅栏之间按满的比例拉平，
 * 其他能收 Forge Energy 的方块直接送；</li>
 * <li>不往“给它送过电的那一面”回送（{@link #inputFaces}），免得和接线器之类来回倒电；那一面的方块换了就重新算；</li>
 * <li>别的方块不能从电栅栏里抽电（只能它主动送）。</li>
 * </ul>
 */
public class TileEntityElectricFence extends TileEntity implements ITickable
{
    public static final String TAG_ENERGY = "Energy";
    public static final String TAG_CAPACITY = "Capacity";
    private static final String TAG_INPUT_FACES = "InputFaces";

    private int energy = 0;
    private int capacity = FencePower.DEFAULT_CAPACITY;
    /** 给它送过电的面（位掩码，按 EnumFacing 的序号） */
    private int inputFaces = 0;

    private final IEnergyStorage[] handlers = new IEnergyStorage[7];
    @Nullable
    private Ic2Energy.Sink ic2Sink;
    private boolean ic2Checked;

    public int getEnergy()
    {
        return energy;
    }

    public int getCapacity()
    {
        return capacity;
    }

    public boolean canZap()
    {
        return energy >= FencePower.ZAP_COST;
    }

    /** 电一次：扣一份电（只在服务端调） */
    public void useZap()
    {
        if(energy >= FencePower.ZAP_COST)
        {
            energy -= FencePower.ZAP_COST;
            this.changed();
        }
    }

    /** 从物品读电量、容量（放下时调；物品没记录时容量按默认、电量 0） */
    public void readFromItem(ItemStack stack)
    {
        NBTTagCompound tag = stack.getTagCompound();
        this.capacity = FencePower.sanitizeCapacity(tag != null ? tag.getInteger(TAG_CAPACITY) : 0);
        this.energy = FencePower.sanitizeEnergy(tag != null ? tag.getInteger(TAG_ENERGY) : 0, this.capacity);
        this.markDirty();
    }

    /** 写进物品（拆下时调） */
    public void writeToItem(ItemStack stack)
    {
        writeItemTag(stack, energy, capacity);
    }

    public static void writeItemTag(ItemStack stack, int energy, int capacity)
    {
        NBTTagCompound tag = stack.getTagCompound();
        if(tag == null)
        {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger(TAG_CAPACITY, capacity);
        tag.setInteger(TAG_ENERGY, energy);
    }

    /** 邻居方块变了：那一面不再当作“送电进来的面” */
    public void onNeighborChanged(BlockPos fromPos)
    {
        for(EnumFacing facing : EnumFacing.VALUES)
        {
            if(pos.offset(facing).equals(fromPos))
            {
                inputFaces &= ~(1 << facing.getIndex());
            }
        }
    }

    private void changed()
    {
        if(world != null)
        {
            world.markChunkDirty(pos, this);
        }
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote)
            return;

        if(!ic2Checked)
        {
            ic2Checked = true;
            ic2Sink = Ic2Energy.createSink(this, FencePower.TRANSFER_PER_FACE);
        }
        if(ic2Sink != null)
        {
            int got = ic2Sink.take(capacity - energy, FencePower.TRANSFER_PER_FACE);
            if(got > 0)
            {
                energy = Math.min(capacity, energy + got);
                this.changed();
            }
        }

        if(energy <= 0)
            return;

        boolean moved = false;
        for(EnumFacing facing : EnumFacing.VALUES)
        {
            if(energy <= 0)
                break;
            BlockPos other = pos.offset(facing);
            if(!world.isBlockLoaded(other))
                continue;
            TileEntity tileEntity = world.getTileEntity(other);
            if(tileEntity == null)
                continue;
            if(tileEntity instanceof TileEntityElectricFence)
            {
                TileEntityElectricFence fence = (TileEntityElectricFence) tileEntity;
                int amount = FencePower.equalizeTransfer(energy, capacity, fence.energy, fence.capacity, FencePower.TRANSFER_PER_FACE);
                if(amount > 0)
                {
                    energy -= amount;
                    fence.energy += amount;
                    fence.changed();
                    moved = true;
                }
                continue;
            }
            if((inputFaces & (1 << facing.getIndex())) != 0)
                continue;
            EnumFacing side = facing.getOpposite();
            if(!tileEntity.hasCapability(CapabilityEnergy.ENERGY, side))
                continue;
            IEnergyStorage storage = tileEntity.getCapability(CapabilityEnergy.ENERGY, side);
            if(storage == null || !storage.canReceive())
                continue;
            int accepted = storage.receiveEnergy(Math.min(FencePower.TRANSFER_PER_FACE, energy), false);
            if(accepted > 0)
            {
                energy -= Math.min(accepted, energy);
                moved = true;
            }
        }
        if(moved)
        {
            this.changed();
        }
    }

    private int receive(@Nullable EnumFacing from, int maxReceive, boolean simulate)
    {
        int received = FencePower.receivable(energy, capacity, maxReceive);
        if(!simulate && received > 0)
        {
            energy += received;
            if(from != null)
            {
                inputFaces |= 1 << from.getIndex();
            }
            this.changed();
        }
        return received;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);
        this.capacity = FencePower.sanitizeCapacity(compound.hasKey(TAG_CAPACITY, Constants.NBT.TAG_INT) ? compound.getInteger(TAG_CAPACITY) : 0);
        this.energy = FencePower.sanitizeEnergy(compound.getInteger(TAG_ENERGY), this.capacity);
        this.inputFaces = compound.getByte(TAG_INPUT_FACES) & 0x3F;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);
        compound.setInteger(TAG_ENERGY, energy);
        compound.setInteger(TAG_CAPACITY, capacity);
        compound.setByte(TAG_INPUT_FACES, (byte) inputFaces);
        return compound;
    }

    @Override
    public void invalidate()
    {
        super.invalidate();
        if(ic2Sink != null)
        {
            ic2Sink.unload();
            ic2Sink = null;
        }
    }

    @Override
    public void onChunkUnload()
    {
        super.onChunkUnload();
        if(ic2Sink != null)
        {
            ic2Sink.unload();
            ic2Sink = null;
        }
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
    {
        return capability == CapabilityEnergy.ENERGY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
        {
            int index = facing == null ? 6 : facing.getIndex();
            if(handlers[index] == null)
            {
                handlers[index] = new Port(facing);
            }
            return CapabilityEnergy.ENERGY.cast(handlers[index]);
        }
        return super.getCapability(capability, facing);
    }

    /** 某一面的 Forge Energy 接口：只收不出 */
    private class Port implements IEnergyStorage
    {
        @Nullable
        private final EnumFacing facing;

        Port(@Nullable EnumFacing facing)
        {
            this.facing = facing;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate)
        {
            if(world == null || world.isRemote)
                return 0;
            return receive(facing, maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate)
        {
            return 0;
        }

        @Override
        public int getEnergyStored()
        {
            return energy;
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
