package com.mrcrayfish.furniture.blocks;

import com.google.common.collect.Lists;
import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.PotionTypes;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import java.util.List;
import com.mrcrayfish.furniture.tileentity.TileEntityBasin;
import com.mrcrayfish.furniture.water.FurnitureTank;
import com.mrcrayfish.furniture.water.WaterBasins;
import net.minecraft.tileentity.TileEntity;
import javax.annotation.Nullable;

public class BlockBasin extends BlockFurniture
{
    public static final AxisAlignedBB[] PILLAR = new AxisAlignedBB[]{new AxisAlignedBB(3 * 0.0625, 0, 8 * 0.0625, 13 * 0.0625, 11 * 0.0625, 1), new AxisAlignedBB(0, 0, 3 * 0.0625, 8 * 0.0625, 11 * 0.0625, 13 * 0.0625), new AxisAlignedBB(3 * 0.0625, 0, 0, 13 * 0.0625, 11 * 0.0625, 8 * 0.0625), new AxisAlignedBB(8 * 0.0625, 0, 3 * 0.0625, 1, 11 * 0.0625, 13 * 0.0625)};
    public static final AxisAlignedBB TOP = new AxisAlignedBB(0, 11 * 0.0625, 0, 1, 1, 1);

    public static final PropertyBool FILLED = PropertyBool.create("filled");

    public BlockBasin(Material material)
    {
        super(material);
        this.setHardness(1.0F);
        this.setSoundType(SoundType.STONE);
        this.setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH).withProperty(FILLED, Boolean.FALSE));
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        if(placer instanceof EntityPlayer)
        {
            Triggers.trigger(Triggers.PLACE_BATHTROOM_FURNITURE, (EntityPlayer) placer);
        }
        super.onBlockPlacedBy(world, pos, state, placer, stack);
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean isActualState)
    {
        List<AxisAlignedBB> list = getCollisionBoxList(this.getActualState(state, worldIn, pos));
        for(AxisAlignedBB box : list)
        {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, box);
        }
    }

    @Override
    public void onBlockHarvested(World world, BlockPos pos, IBlockState state, EntityPlayer player)
    {
        world.updateComparatorOutputLevel(pos, this);
    }

    @Override
    public void onPlayerDestroy(World world, BlockPos pos, IBlockState state)
    {
        world.updateComparatorOutputLevel(pos, this);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        // 悠然服务器魔改：水存在方块实体里，开着时从下面一格的流体容器抽水（见 WaterBasins）；不再有无限水
        FurnitureTank tank = WaterBasins.basinTank(worldIn, pos);
        return WaterBasins.activate(worldIn, pos, playerIn, hand, tank, () -> WaterBasins.refreshFilled(worldIn, pos, tank), true, "cfm.message.basin", pos.down());
    }

    @Override
    public boolean hasTileEntity(IBlockState state)
    {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state)
    {
        return new TileEntityBasin();
    }

    private void createSplashParticle(World world, BlockPos pos)
    {
        for(int i = 0; i < 5; i++)
        {
            world.spawnParticle(EnumParticleTypes.WATER_SPLASH, pos.getX() + 0.3 + 0.4 * RANDOM.nextDouble(), pos.getY() + 1.05, pos.getZ() + 0.3 + 0.4 * RANDOM.nextDouble(), 0, 0, 0);
        }
    }

    @Override
    public IBlockState getStateFromMeta(int meta)
    {
        return this.getDefaultState().withProperty(FACING, EnumFacing.byHorizontalIndex(meta)).withProperty(FILLED, meta / 4 >= 1);
    }

    @Override
    public int getMetaFromState(IBlockState state)
    {
        return state.getValue(FACING).getHorizontalIndex() + (state.getValue(FILLED) ? 4 : 0);
    }

    @Override
    protected BlockStateContainer createBlockState()
    {
        return new BlockStateContainer(this, FACING, FILLED);
    }


    @Override
    public boolean hasComparatorInputOverride(IBlockState state)
    {
        return true;
    }

    @Override
    public int getComparatorInputOverride(IBlockState state, World world, BlockPos pos)
    {
        return world.getBlockState(pos).getValue(FILLED) ? 1 : 0;
    }

    private List<AxisAlignedBB> getCollisionBoxList(IBlockState state)
    {
        List<AxisAlignedBB> list = Lists.newArrayList();
        EnumFacing facing = state.getValue(FACING);
        list.add(PILLAR[facing.getHorizontalIndex()]);
        list.add(TOP);
        return list;
    }

    @Override
    public RayTraceResult collisionRayTrace(IBlockState blockState, World worldIn, BlockPos pos, Vec3d start, Vec3d end)
    {
        List<RayTraceResult> list = Lists.newArrayList();

        for(AxisAlignedBB axisalignedbb : getCollisionBoxList(this.getActualState(blockState, worldIn, pos)))
        {
            list.add(this.rayTrace(pos, start, end, axisalignedbb));
        }

        RayTraceResult raytraceresult1 = null;
        double d1 = 0.0D;

        for(RayTraceResult raytraceresult : list)
        {
            if(raytraceresult != null)
            {
                double d0 = raytraceresult.hitVec.squareDistanceTo(end);

                if(d0 > d1)
                {
                    raytraceresult1 = raytraceresult;
                    d1 = d0;
                }
            }
        }

        return raytraceresult1;
    }
}
