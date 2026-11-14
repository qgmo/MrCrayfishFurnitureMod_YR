package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.util.CollisionHelper;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;
import com.mrcrayfish.furniture.util.WaterRules;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;

public class BlockTap extends BlockFurniture
{
    private static final AxisAlignedBB BOUNDING_BOX_NORTH = CollisionHelper.getBlockBounds(EnumFacing.NORTH, 0.125, 0.0, 0.4, 0.5625, 1.0, 0.6);
    private static final AxisAlignedBB BOUNDING_BOX_EAST = CollisionHelper.getBlockBounds(EnumFacing.EAST, 0.125, 0.0, 0.4, 0.5625, 1.0, 0.6);
    private static final AxisAlignedBB BOUNDING_BOX_SOUTH = CollisionHelper.getBlockBounds(EnumFacing.SOUTH, 0.125, 0.0, 0.4, 0.5625, 1.0, 0.6);
    private static final AxisAlignedBB BOUNDING_BOX_WEST = CollisionHelper.getBlockBounds(EnumFacing.WEST, 0.125, 0.0, 0.4, 0.5625, 1.0, 0.6);
    private static final AxisAlignedBB[] BOUNDING_BOX = {BOUNDING_BOX_SOUTH, BOUNDING_BOX_WEST, BOUNDING_BOX_NORTH, BOUNDING_BOX_EAST};

    public BlockTap(Material material)
    {
        super(material);
        this.setHardness(0.5F);
        this.setSoundType(SoundType.STONE);
    }

    /**
     * 悠然服务器魔改（服主 11-14）：从下面一格的 Forge 流体容器抽 1000 mB，在前面放一格；任何流体都行，抽到什么放什么。
     * 放置用 Forge 的 {@link FluidUtil#tryPlaceFluid}（不直接 setBlock），装了天圆地方等改流体的模组时由它们接管。
     * 原来要求下面第 2 格是水源并把它删掉（无限水），去掉了。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        // 潜行 + 空手右键留给 Carry On
        if(playerIn.isSneaking() && playerIn.getHeldItem(hand).isEmpty())
            return false;
        if(!worldIn.isRemote)
        {
            BlockPos front = pos.offset(state.getValue(FACING).getOpposite());
            IFluidHandler source = worldIn.isBlockLoaded(pos.down()) ? FluidUtil.getFluidHandler(worldIn, pos.down(), EnumFacing.UP) : null;
            FluidStack fluid = source != null ? source.drain(WaterRules.TAP_PER_BLOCK, false) : null;
            if(fluid == null || fluid.amount < WaterRules.TAP_PER_BLOCK || fluid.getFluid() == null)
            {
                playerIn.sendStatusMessage(new TextComponentTranslation("cfm.message.tap_nowater"), true);
                return true;
            }
            if(!worldIn.isBlockLoaded(front) || !worldIn.isBlockModifiable(playerIn, front) || !playerIn.canPlayerEdit(front, state.getValue(FACING), playerIn.getHeldItem(hand)))
            {
                playerIn.sendStatusMessage(new TextComponentTranslation("cfm.message.tap_blocked"), true);
                return true;
            }
            FluidStack resource = new FluidStack(fluid, WaterRules.TAP_PER_BLOCK);
            if(!FluidUtil.tryPlaceFluid(playerIn, worldIn, front, source, resource))
            {
                playerIn.sendStatusMessage(new TextComponentTranslation("cfm.message.tap_blocked"), true);
            }
        }
        return true;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        EnumFacing facing = state.getValue(FACING);
        return BOUNDING_BOX[facing.getHorizontalIndex()];
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean p_185477_7_)
    {
        EnumFacing facing = state.getValue(FACING);
        addCollisionBoxToList(pos, entityBox, collidingBoxes, BOUNDING_BOX[facing.getHorizontalIndex()]);
    }

}
