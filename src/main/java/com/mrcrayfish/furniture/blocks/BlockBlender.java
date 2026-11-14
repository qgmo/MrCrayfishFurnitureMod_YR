package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.init.FurnitureItems;
import com.mrcrayfish.furniture.tileentity.TileEntityBlender;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

public class BlockBlender extends BlockFurnitureTile
{
    public static final AxisAlignedBB BOUNDING_BOX = new AxisAlignedBB(4 * 0.0625, 0.0, 4 * 0.0625, 12 * 0.0625, 1.0, 12 * 0.0625);
    public static final AxisAlignedBB COLLISION_BOX = new AxisAlignedBB(4.5 * 0.0625, 0, 4.5 * 0.0625, 11.5 * 0.0625, 15 * 0.0625, 11.5 * 0.0625);

    public BlockBlender(Material material)
    {
        super(material);
        this.setHardness(0.5F);
        this.setSoundType(SoundType.GLASS);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        if(placer instanceof EntityPlayer)
        {
            Triggers.trigger(Triggers.PLACE_APPLIANCE, (EntityPlayer) placer);
        }
        super.onBlockPlacedBy(world, pos, state, placer, stack);
    }

    /** 能不能开始搅拌（两端都调，判断一致）：没有做好的饮料、没在搅拌、原料能做成饮料 */
    public static boolean canStart(TileEntityBlender blender)
    {
        return blender.drinkCount == 0 && !blender.isBlending() && blender.hasValidIngredients();
    }

    /** 开始搅拌（服务端，空手潜行 + 左键，见 {@code SneakLeftClick}；原来是潜行 + 空手右键） */
    public static void start(World world, BlockPos pos, EntityPlayer player)
    {
        TileEntity tileEntity = world.getTileEntity(pos);
        if(!(tileEntity instanceof TileEntityBlender) || !canStart((TileEntityBlender) tileEntity))
            return;
        ((TileEntityBlender) tileEntity).startBlending();
        TileEntityUtil.markBlockForUpdate(world, pos);
        world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        ItemStack heldItem = playerIn.getHeldItem(hand);
        TileEntity tileEntity = worldIn.getTileEntity(pos);
        if(tileEntity instanceof TileEntityBlender)
        {
            TileEntityBlender tileEntityBlender = (TileEntityBlender) tileEntity;
            if(tileEntityBlender.drinkCount == 0)
            {
                if(!heldItem.isEmpty() && !tileEntityBlender.isFull() && !tileEntityBlender.isBlending())
                {
                    tileEntityBlender.addIngredient(heldItem.copy());
                    TileEntityUtil.markBlockForUpdate(worldIn, pos);
                    playerIn.setHeldItem(hand, ItemStack.EMPTY);
                    return true;
                }
                else
                {
                    // 开始搅拌改成空手潜行 + 左键（见 SneakLeftClick）：潜行 + 空手右键留给 Carry On（悠然服务器魔改）
                    if(!tileEntityBlender.isBlending() && !playerIn.isSneaking())
                    {
                        tileEntityBlender.removeIngredient();
                    }
                }
            }
            else
            {
                if(!heldItem.isEmpty() && tileEntityBlender.hasDrink())
                {
                    if(heldItem.getItem() == FurnitureItems.CUP)
                    {
                        if(heldItem.getCount() == 0 | heldItem.getCount() == 1)
                        {
                            playerIn.setHeldItem(hand, tileEntityBlender.getDrink());
                        }
                        else
                        {
                            playerIn.inventory.addItemStackToInventory(tileEntityBlender.getDrink());
                            heldItem.shrink(1);
                        }
                        playerIn.playSound(SoundEvents.ITEM_BOTTLE_FILL, 1.0F, 1.5F);
                        tileEntityBlender.drinkCount--;
                        TileEntityUtil.markBlockForUpdate(worldIn, pos);
                    }
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        return BOUNDING_BOX;
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean p_185477_7_)
    {
        addCollisionBoxToList(pos, entityBox, collidingBoxes, COLLISION_BOX);
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityBlender();
    }

    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getRenderLayer()
    {
        return BlockRenderLayer.TRANSLUCENT;
    }

    @Override
    public int getComparatorInputOverride(IBlockState state, World world, BlockPos pos)
    {
        if(world.getTileEntity(pos) instanceof TileEntityBlender)
        {
            TileEntityBlender blender = (TileEntityBlender) world.getTileEntity(pos);
            return blender.isBlending() ? 1 : 0;
        }
        return 0;
    }
}
