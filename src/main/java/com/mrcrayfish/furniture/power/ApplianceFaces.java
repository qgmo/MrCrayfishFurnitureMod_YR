package com.mrcrayfish.furniture.power;

import com.mrcrayfish.furniture.blocks.BlockFurniture;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * 电器从哪些面进电（服主 11-14）：除吊扇外只能从下面进；吊扇只能从上面进；烤箱、冰箱除了下面，背面也能进。
 * 家具的 FACING 是放下时玩家面朝的方向，正面朝着玩家，所以背面就是 FACING 那一面。
 */
public final class ApplianceFaces
{
    private ApplianceFaces() {}

    public static Predicate<EnumFacing> bottom()
    {
        return facing -> facing == EnumFacing.DOWN;
    }

    public static Predicate<EnumFacing> top()
    {
        return facing -> facing == EnumFacing.UP;
    }

    public static Predicate<EnumFacing> bottomAndBack(TileEntity tile)
    {
        return facing -> facing == EnumFacing.DOWN || (facing != null && facing == back(tile));
    }

    public static Predicate<EnumFacing> backOnly(TileEntity tile)
    {
        return facing -> facing != null && facing == back(tile);
    }

    /** 家具的背面；不是家具方块时 null */
    @Nullable
    public static EnumFacing back(TileEntity tile)
    {
        World world = tile.getWorld();
        if(world == null)
            return null;
        IBlockState state = world.getBlockState(tile.getPos());
        if(state.getBlock() instanceof BlockFurniture && state.getPropertyKeys().contains(BlockFurniture.FACING))
            return state.getValue(BlockFurniture.FACING);
        return null;
    }
}
