package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.util.WaterRules;
import com.mrcrayfish.furniture.water.FurnitureTank;
import com.mrcrayfish.furniture.water.IWaterBasin;
import com.mrcrayfish.furniture.water.WaterBasins;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;

/**
 * 盆池（悠然服务器魔改）：自己装 1000 mB 水，开着时每 tick 从下面一格的流体容器抽 50 mB，满了自动关；水会蒸发。
 * 外观的“有水”跟着水量走（{@link WaterBasins#refreshFilled}）。旧存档的盆池没有方块实体，按空、关着处理。
 */
public class TileEntityBasin extends TileEntitySyncClient implements ITickable, IWaterBasin
{
    private final FurnitureTank tank = new FurnitureTank(WaterRules.BASIN_CAPACITY, WaterRules.BASIN_FILL_PER_TICK, this::changed);

    private void changed()
    {
        if(world != null)
        {
            world.markChunkDirty(pos, this);
        }
    }

    @Override
    public FurnitureTank getTank()
    {
        return tank;
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote)
            return;
        tank.tick(world, new BlockPos[]{pos.down()}, new EnumFacing[]{EnumFacing.UP});
        WaterBasins.refreshFilled(world, pos, tank);
    }

    /**
     * “有水”的方块状态变了不换方块实体（Forge 对模组的方块实体默认是方块状态一变就重建，水会丢）
     */
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState)
    {
        return oldState.getBlock() != newState.getBlock();
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        tank.readFromNBT(tagCompound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tank.writeToNBT(tagCompound);
        return tagCompound;
    }
}
