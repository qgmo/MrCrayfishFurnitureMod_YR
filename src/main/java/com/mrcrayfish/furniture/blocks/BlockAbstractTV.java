package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Author: MrCrayfish
 */
public abstract class BlockAbstractTV extends BlockFurnitureTile
{
    private int width;
    private int height;
    private double screenYOffset;
    private double screenZOffset;

    public BlockAbstractTV(Material material, int width, int height, double screenYOffset, double screenZOffset)
    {
        super(material);
        this.width = width;
        this.height = height;
        this.screenYOffset = screenYOffset;
        this.screenZOffset = screenZOffset;
    }

    /**
     * 右键屏幕正面进入大屏（悠然服务器魔改，服主 11-14）。潜行 + 空手右键留给 Carry On，这里不处理；
     * 频道设置只从大屏右下角的“设置”按钮进。拿着遥控器时右键被遥控器截住，走不到这里。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        if(playerIn.isSneaking() || hand != EnumHand.MAIN_HAND || !isScreenFace(state, facing))
            return false;
        if(worldIn.isRemote)
        {
            MrCrayfishFurnitureMod.proxy.openTvScreen(pos);
        }
        return true;
    }

    /** 屏幕在正面：家具的 FACING 指向背面 */
    public static boolean isScreenFace(IBlockState state, EnumFacing face)
    {
        return state.getPropertyKeys().contains(FACING) && face == state.getValue(FACING).getOpposite();
    }

    public double getScreenZOffset(IBlockState state)
    {
        return screenZOffset;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityTV(width, height, screenYOffset, screenZOffset);
    }
}
