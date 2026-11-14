package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.init.FurnitureItems;
import com.mrcrayfish.furniture.tileentity.TileEntityCup;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

public class BlockCup extends Block implements ITileEntityProvider
{
    private static final AxisAlignedBB BOUNDING_BOX = new AxisAlignedBB(5 * 0.0625, 0.0, 5 * 0.0625, 11 * 0.0625, 7.5 * 0.0625, 11 * 0.0625);

    public BlockCup(Material material)
    {
        super(material);
        this.setSoundType(SoundType.GLASS);
        this.setHardness(0.1F);
        this.setDefaultState(this.blockState.getBaseState());
    }

    /**
     * 放在地上的杯子能不能喝（两端都调，判断一致）：不是创造模式、饿了、杯里有饮料。
     * 喝改成空手潜行 + 左键（见 {@code SneakLeftClick}）：原来的潜行 + 空手右键留给 Carry On（悠然服务器魔改）。
     */
    public static boolean canDrink(EntityPlayer player, TileEntityCup tileEntityCup)
    {
        if(player.isCreative() || !player.getFoodStats().needFood())
            return false;
        ItemStack cup = tileEntityCup.getDrink();
        return cup != null && cup.hasTagCompound();
    }

    /** 喝掉放在地上的杯子里的饮料（服务端） */
    public static void drink(World world, BlockPos pos, EntityPlayer player)
    {
        TileEntity tileEntity = world.getTileEntity(pos);
        if(!(tileEntity instanceof TileEntityCup))
            return;
        TileEntityCup tileEntityCup = (TileEntityCup) tileEntity;
        if(!canDrink(player, tileEntityCup))
            return;
        ItemStack cup = tileEntityCup.getDrink();
        tileEntityCup.clear();
        tileEntityCup.markDirty();
        tileEntityCup.sync();
        int heal = cup.getTagCompound().getInteger("HealAmount");
        player.getFoodStats().addStats(heal, 0.5F);
        world.playSound(null, player.posX, player.posY, player.posZ, SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 1.0F, 1.0F);
        world.playSound(null, player.posX, player.posY, player.posZ, SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state)
    {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state)
    {
        return false;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        if(stack.hasTagCompound())
        {
            NBTTagCompound nbt = stack.getTagCompound();
            if(nbt.hasKey("Colour"))
            {
                int[] rgb = nbt.getIntArray("Colour");
                TileEntityCup tileEntityCup = (TileEntityCup) world.getTileEntity(pos);
                tileEntityCup.setColour(rgb);
                tileEntityCup.setItem(stack);
            }
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        return BOUNDING_BOX;
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean p_185477_7_)
    {
        addCollisionBoxToList(pos, entityBox, collidingBoxes, BOUNDING_BOX);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta)
    {
        return new TileEntityCup();
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, BlockPos pos, IBlockState state, TileEntity te, ItemStack stack)
    {
        if(te instanceof TileEntityCup)
        {
            TileEntityCup tileEntityCup = (TileEntityCup) te;
            EntityItem item = new EntityItem(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, tileEntityCup.getDrink() != null ? tileEntityCup.getDrink().copy() : new ItemStack(FurnitureItems.CUP));
            item.setDefaultPickupDelay();
            world.spawnEntity(item);
        }
        else
        {
            EntityItem item = new EntityItem(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, new ItemStack(FurnitureItems.CUP));
            item.setDefaultPickupDelay();
            world.spawnEntity(item);
        }
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player)
    {
        if(world.getTileEntity(pos) instanceof TileEntityCup)
        {
            TileEntityCup tileEntityCup = (TileEntityCup) world.getTileEntity(pos);
            if(tileEntityCup.getDrink() != null)
            {
                return tileEntityCup.getDrink().copy();
            }
        }
        return new ItemStack(FurnitureItems.CUP);
    }

    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getRenderLayer()
    {
        return BlockRenderLayer.TRANSLUCENT;
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess worldIn, IBlockState state, BlockPos pos, EnumFacing face)
    {
        return BlockFaceShape.UNDEFINED;
    }
}
