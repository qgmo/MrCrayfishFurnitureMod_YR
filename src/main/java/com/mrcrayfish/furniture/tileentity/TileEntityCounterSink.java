package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.util.WaterRules;
import com.mrcrayfish.furniture.water.FurnitureTank;
import com.mrcrayfish.furniture.water.IWaterBasin;
import com.mrcrayfish.furniture.water.WaterBasins;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

/**
 * 橱柜水槽（悠然服务器魔改）：颜色同厨房台面，另外自己装 1000 mB 水，规则同盆池（{@link TileEntityBasin}）。
 * 旧存档的水槽用的是厨房台面的方块实体，第一次操作时换成这个（{@link WaterBasins#basinTank}），水按空。
 */
public class TileEntityCounterSink extends TileEntityKitchenCounter implements ITickable, IWaterBasin
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
