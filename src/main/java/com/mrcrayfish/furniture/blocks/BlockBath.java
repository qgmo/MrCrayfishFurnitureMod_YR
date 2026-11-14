package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.init.FurnitureBlocks;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.tileentity.TileEntityBath;
import com.mrcrayfish.furniture.util.CollisionHelper;
import com.mrcrayfish.furniture.util.PlacementHelper;
import com.mrcrayfish.furniture.util.SeatUtil;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.PotionTypes;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionUtils;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;

import java.util.List;
import java.util.Random;
import com.mrcrayfish.furniture.water.WaterBasins;

public class BlockBath extends BlockFurnitureTile
{
    public static final PropertyInteger WATER_LEVEL = PropertyInteger.create("level", 0, 16);

    /* Collision Bounds */
    public static final AxisAlignedBB BOTTOM = new AxisAlignedBB(0, 0, 0, 1, 2 * 0.0625, 1);
    public static final AxisAlignedBB SIDE_NORTH = CollisionHelper.getBlockBounds(EnumFacing.NORTH, 0.0, 0.125, 0.0, 1.0, 0.875, 0.125);
    public static final AxisAlignedBB SIDE_EAST = CollisionHelper.getBlockBounds(EnumFacing.EAST, 0.0, 0.125, 0.0, 1.0, 0.875, 0.125);
    public static final AxisAlignedBB SIDE_SOUTH = CollisionHelper.getBlockBounds(EnumFacing.SOUTH, 0.0, 0.125, 0.0, 1.0, 0.875, 0.125);
    public static final AxisAlignedBB SIDE_WEST = CollisionHelper.getBlockBounds(EnumFacing.WEST, 0.0, 0.125, 0.0, 1.0, 0.875, 0.125);

    public static final AxisAlignedBB[] TOP_BOXES = {new AxisAlignedBB(0, 0, -1, 1, 15 * 0.0625, 1), new AxisAlignedBB(0, 0, 0, 2, 15 * 0.0625, 1), new AxisAlignedBB(0, 0, 0, 1, 15 * 0.0625, 2), new AxisAlignedBB(-1, 0, 0, 1, 15 * 0.0625, 1)};
    public static final AxisAlignedBB[] BOTTOM_BOXES = {new AxisAlignedBB(0, 0, 0, 1, 15 * 0.0625, 2), new AxisAlignedBB(-1, 0, 0, 1, 15 * 0.0625, 1), new AxisAlignedBB(0, 0, -1, 1, 15 * 0.0625, 1), new AxisAlignedBB(0, 0, 0, 2, 15 * 0.0625, 1)};

    public BlockBath(Material material, boolean top)
    {
        super(material);
        this.setHardness(1.0F);
        this.setSoundType(SoundType.STONE);
        if(top) this.setCreativeTab(null);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta, EntityLivingBase placer)
    {
        IBlockState state = super.getStateForPlacement(world, pos, facing, hitX, hitY, hitZ, meta, placer);
        return state.withProperty(WATER_LEVEL, 0);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        BlockPos second = pos.offset(placer.getHorizontalFacing());
        if(!world.getBlockState(second).getBlock().isReplaceable(world, second))
        {
            // 第二格放不下（ItemBath 已在放置前核对，这里兜底其它放置途径）：撤销底块，不覆盖旁边的方块
            world.setBlockToAir(pos);
            return;
        }
        world.setBlockState(second, FurnitureBlocks.BATH_2.getDefaultState().withProperty(FACING, state.getValue(FACING)).withProperty(WATER_LEVEL, state.getValue(WATER_LEVEL)));
        if(placer instanceof EntityPlayer)
        {
            Triggers.trigger(Triggers.PLACE_BATHTROOM_FURNITURE, (EntityPlayer) placer);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        EnumFacing facing = state.getValue(FACING);
        return state.getBlock() == FurnitureBlocks.BATH_1 ? BOTTOM_BOXES[facing.getHorizontalIndex()] : TOP_BOXES[facing.getHorizontalIndex()];
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean p_185477_7_)
    {
        EnumFacing facing = state.getValue(FACING);
        addCollisionBoxToList(pos, entityBox, collidingBoxes, BOTTOM);
        if(this == FurnitureBlocks.BATH_1)
        {
            if(facing != EnumFacing.WEST)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_NORTH);
            if(facing != EnumFacing.EAST)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_SOUTH);
            if(facing != EnumFacing.NORTH)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_EAST);
            if(facing != EnumFacing.SOUTH)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_WEST);
        }
        else
        {
            if(facing != EnumFacing.EAST)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_NORTH);
            if(facing != EnumFacing.WEST)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_SOUTH);
            if(facing != EnumFacing.SOUTH)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_EAST);
            if(facing != EnumFacing.NORTH)
                addCollisionBoxToList(pos, entityBox, collidingBoxes, SIDE_WEST);
        }
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        // 悠然服务器魔改：水存在浴缸头那一半里，开着时从两半下面的流体容器抽水（见 TileEntityBath、WaterBasins）；不再有无限水。
        // 空手右键坐下（照旧）；拿桶舀水、倒水；拿别的东西右键开 / 关（也可以潜行 + 左键）
        ItemStack heldItem = playerIn.getHeldItem(hand);
        if(heldItem.isEmpty())
        {
            if(playerIn.isSneaking())
                return false;
            return SeatUtil.sitOnBlock(worldIn, pos.getX(), pos.getY(), pos.getZ(), playerIn, 0);
        }
        TileEntity tileEntity = worldIn.getTileEntity(pos);
        TileEntityBath master = tileEntity instanceof TileEntityBath ? ((TileEntityBath) tileEntity).getMaster() : null;
        if(master == null)
            return true;
        return WaterBasins.activate(worldIn, pos, playerIn, hand, master.getTank(), master::refreshLevel, true, "cfm.message.bath", sources(master));
    }

    /** 浴缸从哪两格抽水（两半各自的下面一格） */
    public static BlockPos[] sources(TileEntityBath master)
    {
        BlockPos partner = master.getPartnerPos();
        return partner != null ? new BlockPos[]{partner.down(), master.getPos().down()} : new BlockPos[]{master.getPos().down()};
    }

    @Override
    public void onBlockHarvested(World worldIn, BlockPos pos, IBlockState state, EntityPlayer player)
    {
        if(this == FurnitureBlocks.BATH_1)
        {
            BlockPos other = pos.offset(state.getValue(FACING));
            if(PlacementHelper.isPartner(worldIn, other, b -> b == FurnitureBlocks.BATH_2))
                worldIn.destroyBlock(other, false);
        }
        else
        {
            BlockPos other = pos.offset(state.getValue(FACING).getOpposite());
            if(PlacementHelper.isPartner(worldIn, other, b -> b == FurnitureBlocks.BATH_1))
                worldIn.destroyBlock(other, false);
        }
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityBath();
    }

    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune)
    {
        return new ItemStack(FurnitureBlocks.BATH_1).getItem();
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player)
    {
        return new ItemStack(FurnitureBlocks.BATH_1);
    }

    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos)
    {
        if(world.getTileEntity(pos) instanceof TileEntityBath)
        {
            TileEntityBath bath = (TileEntityBath) world.getTileEntity(pos);
            return state.withProperty(WATER_LEVEL, bath.getWaterLevel());
        }
        return state.withProperty(WATER_LEVEL, 0);
    }

    @Override
    protected BlockStateContainer createBlockState()
    {
        return new BlockStateContainer(this, FACING, WATER_LEVEL);
    }

    public static boolean canPlaceBath(World world, BlockPos pos, EnumFacing facing)
    {
        return (world.isAirBlock(pos) && world.isAirBlock(pos.offset(facing, 1)));
    }


    @Override
    public int getComparatorInputOverride(IBlockState state, World world, BlockPos pos)
    {
        TileEntityBath bath = (TileEntityBath) world.getTileEntity(pos);
        return bath.getWaterLevel();
    }
}
