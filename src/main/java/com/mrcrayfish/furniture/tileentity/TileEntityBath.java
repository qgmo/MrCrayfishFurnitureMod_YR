package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.blocks.BlockFurniture;
import com.mrcrayfish.furniture.init.FurnitureBlocks;
import com.mrcrayfish.furniture.util.WaterRules;
import com.mrcrayfish.furniture.water.FurnitureTank;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * 浴缸（悠然服务器魔改）：水存在浴缸头那一半（{@code BATH_1}）的方块实体里，容量 16000 mB；开着时每 tick 从两半下面的
 * 流体容器抽 100 mB（先抽出水口那一半下面的），满了自动关；水会蒸发。两半都记着显示用的水位（16 格，按 1000 mB 一格换算）。
 * 旧存档只有水位，按每格 1000 mB 原版水换算。
 */
public class TileEntityBath extends TileEntitySyncClient implements ITickable
{
    private int waterLevel = 0;
    private final FurnitureTank tank = new FurnitureTank(WaterRules.BATH_CAPACITY, WaterRules.BATH_FILL_PER_TICK, this::changed);

    private void changed()
    {
        if(world != null)
        {
            world.markChunkDirty(pos, this);
        }
    }

    public boolean hasWater()
    {
        return waterLevel > 0;
    }

    public boolean isFull()
    {
        return waterLevel == WaterRules.BATH_LEVELS;
    }

    public int getWaterLevel()
    {
        return waterLevel;
    }

    /** 客户端设显示水位（旧的 MessageFillBath 用；服务端现在靠方块实体同步） */
    public void setWaterLevel(int waterLevel)
    {
        this.waterLevel = Math.max(0, Math.min(WaterRules.BATH_LEVELS, waterLevel));
    }

    /** 这一半是不是存水的那一半（浴缸头） */
    public boolean isMaster()
    {
        return world != null && world.getBlockState(pos).getBlock() == FurnitureBlocks.BATH_1;
    }

    /** 另一半的位置；这一格不是浴缸时 null */
    @Nullable
    public BlockPos getPartnerPos()
    {
        if(world == null)
            return null;
        IBlockState state = world.getBlockState(pos);
        if(!state.getPropertyKeys().contains(BlockFurniture.FACING))
            return null;
        EnumFacing facing = state.getValue(BlockFurniture.FACING);
        if(state.getBlock() == FurnitureBlocks.BATH_1)
            return pos.offset(facing);
        if(state.getBlock() == FurnitureBlocks.BATH_2)
            return pos.offset(facing.getOpposite());
        return null;
    }

    /** 存水的那一半；找不到时 null */
    @Nullable
    public TileEntityBath getMaster()
    {
        if(isMaster())
            return this;
        BlockPos partner = getPartnerPos();
        if(partner == null || !world.isBlockLoaded(partner))
            return null;
        TileEntity other = world.getTileEntity(partner);
        return other instanceof TileEntityBath && ((TileEntityBath) other).isMaster() ? (TileEntityBath) other : null;
    }

    /** 浴缸的水（只有浴缸头那一半的有效） */
    public FurnitureTank getTank()
    {
        return tank;
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote || !isMaster())
            return;
        BlockPos partner = getPartnerPos();
        BlockPos[] sources = partner != null ? new BlockPos[]{partner.down(), pos.down()} : new BlockPos[]{pos.down()};
        EnumFacing[] sides = partner != null ? new EnumFacing[]{EnumFacing.UP, EnumFacing.UP} : new EnumFacing[]{EnumFacing.UP};
        tank.tick(world, sources, sides);
        this.refreshLevel();
    }

    /** 水量变了以后更新两半的显示水位（服务端） */
    public void refreshLevel()
    {
        int level = WaterRules.bathLevel(tank.getAmount());
        this.setLevel(level);
        BlockPos partner = getPartnerPos();
        if(partner != null && world.isBlockLoaded(partner))
        {
            TileEntity other = world.getTileEntity(partner);
            if(other instanceof TileEntityBath)
            {
                ((TileEntityBath) other).setLevel(level);
            }
        }
    }

    private void setLevel(int level)
    {
        if(this.waterLevel != level)
        {
            this.waterLevel = level;
            this.markDirty();
            this.sync();
            world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());
        }
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt)
    {
        int old = this.waterLevel;
        super.onDataPacket(net, pkt);
        if(old != this.waterLevel && world != null)
        {
            world.markBlockRangeForRenderUpdate(pos, pos);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.waterLevel = Math.max(0, Math.min(WaterRules.BATH_LEVELS, tagCompound.getInteger("waterLevel")));
        if(FurnitureTank.hasSaved(tagCompound))
        {
            tank.readFromNBT(tagCompound);
        }
        else if(tagCompound.hasKey("waterLevel", Constants.NBT.TAG_INT))
        {
            // 旧存档：按水位换算成原版水
            tank.setLegacyWater(WaterRules.bathAmountFromLevel(this.waterLevel));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setInteger("waterLevel", waterLevel);
        tank.writeToNBT(tagCompound);
        return tagCompound;
    }
}
