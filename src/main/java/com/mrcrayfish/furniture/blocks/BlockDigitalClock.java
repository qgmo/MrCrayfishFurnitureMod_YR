package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.compat.ClockBatteries;
import com.mrcrayfish.furniture.tileentity.IColored;
import com.mrcrayfish.furniture.tileentity.TileEntityDigitalClock;
import com.mrcrayfish.furniture.util.Bounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockColored;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Author: MrCrayfish
 */
public class BlockDigitalClock extends BlockFurnitureTile
{
    private static final AxisAlignedBB[] COLLISION_BOXES = new Bounds(6, 0, 4, 9, 4, 12).getRotatedBounds();
    private static final AxisAlignedBB[] SELECTION_BOXES = new Bounds(5, 0, 3, 10, 5, 13).getRotatedBounds();

    public BlockDigitalClock()
    {
        super(Material.WOOD);
        this.setTranslationKey("digital_clock");
        this.setRegistryName("digital_clock");
        this.setLightLevel(0.5F);
        this.setHardness(0.5F);
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced)
    {
        if(GuiScreen.isShiftKeyDown())
        {
            String info = I18n.format("cfm.digital_clock.info");
            tooltip.addAll(Minecraft.getMinecraft().fontRenderer.listFormattedStringToWidth(info, 150));
        }
        else
        {
            tooltip.add(TextFormatting.YELLOW + I18n.format("cfm.info"));
        }
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean p_185477_7_)
    {
        Block.addCollisionBoxToList(pos, entityBox, collidingBoxes, COLLISION_BOXES[state.getValue(FACING).getHorizontalIndex()]);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        return SELECTION_BOXES[state.getValue(FACING).getHorizontalIndex()];
    }

    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos)
    {
        TileEntity tileEntity = worldIn.getTileEntity(pos);
        if(tileEntity instanceof IColored)
        {
            IColored colored = (IColored) tileEntity;
            state = state.withProperty(BlockColored.COLOR, colored.getColor());
        }
        return state;
    }

    @Override
    public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos, IBlockState state, @Nullable TileEntity tileEntity, ItemStack stack)
    {
        if(tileEntity instanceof TileEntityDigitalClock)
        {
            TileEntityDigitalClock clock = (TileEntityDigitalClock) tileEntity;
            ItemStack itemstack = new ItemStack(this, 1, clock.getColor().getMetadata());
            spawnAsEntity(worldIn, pos, itemstack);
        }
        else
        {
            super.harvestBlock(worldIn, player, pos, state, tileEntity, stack);
        }
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player)
    {
        TileEntity tileEntity = world.getTileEntity(pos);
        if(tileEntity instanceof IColored)
        {
            return new ItemStack(Item.getItemFromBlock(this), 1, ((IColored) tileEntity).getColor().getMetadata());
        }
        return super.getPickBlock(state, target, world, pos, player);
    }

    @Override
    public void onBlockPlacedBy(World worldIn, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        super.onBlockPlacedBy(worldIn, pos, state, placer, stack);
        TileEntity tileEntity = worldIn.getTileEntity(pos);
        if(tileEntity instanceof IColored)
        {
            IColored colored = (IColored) tileEntity;
            colored.setColor(EnumDyeColor.byMetadata(stack.getMetadata()));
        }
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state)
    {
        // 拆掉时（挖掉、爆炸、被别的方块顶掉）电池掉出来
        TileEntity tileEntity = worldIn.getTileEntity(pos);
        if(tileEntity instanceof TileEntityDigitalClock && !worldIn.isRemote)
        {
            ItemStack battery = ((TileEntityDigitalClock) tileEntity).getBattery();
            if(!battery.isEmpty())
            {
                spawnAsEntity(worldIn, pos, battery.copy());
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        if(!worldIn.isRemote)
        {
            TileEntity tileEntity = worldIn.getTileEntity(pos);
            if(!(tileEntity instanceof TileEntityDigitalClock))
                return true;
            TileEntityDigitalClock digitalClock = (TileEntityDigitalClock) tileEntity;
            ItemStack heldItem = playerIn.getHeldItem(hand);
            if(!heldItem.isEmpty() && heldItem.getItem() instanceof ItemDye)
            {
                digitalClock.setTextColorColor(EnumDyeColor.byDyeDamage(heldItem.getMetadata()));
                if(!playerIn.capabilities.isCreativeMode)
                {
                    heldItem.shrink(1);
                }
            }
            else if(!heldItem.isEmpty() && ClockBatteries.isBattery(heldItem))
            {
                // 拿电池右键：放进去；里面已经有电池就和手上的交换
                ItemStack one = heldItem.copy();
                one.setCount(1);
                ItemStack old = digitalClock.swapBattery(one);
                heldItem.shrink(1);
                if(!old.isEmpty())
                {
                    if(heldItem.isEmpty())
                    {
                        playerIn.setHeldItem(hand, old);
                    }
                    else
                    {
                        ItemHandlerHelper.giveItemToPlayer(playerIn, old);
                    }
                }
            }
            else if(heldItem.isEmpty() && playerIn.isSneaking() && digitalClock.hasBattery())
            {
                // 潜行空手右键：取出电池
                ItemHandlerHelper.giveItemToPlayer(playerIn, digitalClock.removeBattery());
            }
            else if(!digitalClock.hasBattery())
            {
                playerIn.sendStatusMessage(new TextComponentTranslation("cfm.message.clock_no_battery"), true);
            }
            else if(!digitalClock.isPowered())
            {
                playerIn.sendStatusMessage(new TextComponentTranslation("cfm.message.clock_battery_empty"), true);
            }
        }
        return true;
    }

    @Override
    protected BlockStateContainer createBlockState()
    {
        return new BlockStateContainer(this, FACING, BlockColored.COLOR);
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityDigitalClock();
    }
}
