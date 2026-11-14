package com.mrcrayfish.furniture.water;

import com.mrcrayfish.furniture.blocks.BlockBasin;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.tileentity.TileEntityCounterSink;
import com.mrcrayfish.furniture.tileentity.TileEntityKitchenCounter;
import com.mrcrayfish.furniture.util.WaterRules;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.init.PotionTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionUtils;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * 盆池、水槽、浴缸共用的操作（悠然服务器魔改）：
 * <ul>
 * <li>拿桶（或别的 Forge 流体容器）右键：空桶舀一桶（够 1000 mB 时），水桶倒入；</li>
 * <li>玻璃瓶 / 水瓶照旧能装水、倒水（原版水，每瓶 {@value #BOTTLE} mB）；</li>
 * <li>空手或拿别的东西右键：开 / 关（开着才从下面抽水，满了自动关）。潜行 + 空手右键留给 Carry On，不处理。</li>
 * </ul>
 */
public final class WaterBasins
{
    /** 一瓶水多少 mB：三瓶约一桶，不凭空多水（服主 11-14） */
    public static final int BOTTLE = 334;

    private static final Random RANDOM = new Random();

    private WaterBasins() {}

    /** 盆池、水槽这一格的水（水槽旧存档的方块实体会先换成新的） */
    @Nullable
    public static FurnitureTank basinTank(World world, BlockPos pos)
    {
        TileEntity tileEntity = world.getTileEntity(pos);
        if(tileEntity instanceof TileEntityKitchenCounter && !(tileEntity instanceof TileEntityCounterSink) && !world.isRemote)
        {
            tileEntity = upgradeSink(world, pos, (TileEntityKitchenCounter) tileEntity);
        }
        return tileEntity instanceof IWaterBasin ? ((IWaterBasin) tileEntity).getTank() : null;
    }

    /** 旧存档的水槽用的是厨房台面的方块实体（只有颜色），换成水槽的（颜色照旧，水按空） */
    private static TileEntity upgradeSink(World world, BlockPos pos, TileEntityKitchenCounter old)
    {
        TileEntityCounterSink sink = new TileEntityCounterSink();
        NBTTagCompound tag = old.writeToNBT(new NBTTagCompound());
        sink.readFromNBT(tag);
        world.setTileEntity(pos, sink);
        sink.markDirty();
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 3);
        return sink;
    }

    /** 盆池、水槽：方块状态里的“有水”跟着水量走（服务端） */
    public static void refreshFilled(World world, BlockPos pos, FurnitureTank tank)
    {
        IBlockState state = world.getBlockState(pos);
        if(state.getPropertyKeys().contains(BlockBasin.FILLED) && state.getValue(BlockBasin.FILLED) != tank.hasWater())
        {
            world.setBlockState(pos, state.withProperty(BlockBasin.FILLED, tank.hasWater()), 3);
            world.updateComparatorOutputLevel(pos, state.getBlock());
        }
    }

    /**
     * 右键（两端都调）。返回 false 表示不处理（潜行 + 空手，留给 Carry On）。
     *
     * @param tank        这件家具的水（客户端可以是 null）
     * @param afterChange 服务端水量变了以后（更新外观、比较器等）
     * @param toggle      true：空手 / 别的东西右键时开关；false：不开关（浴缸空手右键是坐下，由调用方处理）
     */
    public static boolean activate(World world, BlockPos pos, EntityPlayer player, EnumHand hand, @Nullable FurnitureTank tank, Runnable afterChange, boolean toggle, String noSourceKey, BlockPos... sources)
    {
        ItemStack held = player.getHeldItem(hand);
        if(!held.isEmpty() && FluidUtil.getFluidHandler(held) != null)
        {
            if(!world.isRemote && tank != null)
            {
                FluidUtil.interactWithFluidHandler(player, hand, tank);
                afterChange.run();
            }
            return true;
        }
        if(held.getItem() == Items.GLASS_BOTTLE)
        {
            if(!world.isRemote && tank != null)
            {
                FluidStack water = tank.getFluid();
                if(water != null && water.getFluid() == FluidRegistry.WATER && water.amount >= BOTTLE)
                {
                    tank.drain(BOTTLE, true);
                    ItemStack filled = PotionUtils.addPotionToItemStack(new ItemStack(Items.POTIONITEM), PotionTypes.WATER);
                    if(!player.capabilities.isCreativeMode)
                    {
                        held.shrink(1);
                        if(held.isEmpty())
                            player.setHeldItem(hand, filled);
                        else
                            ItemHandlerHelper.giveItemToPlayer(player, filled);
                    }
                    world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 1.0F, 1.0F);
                    afterChange.run();
                }
            }
            return true;
        }
        if(held.getItem() == Items.POTIONITEM && PotionUtils.getPotionFromItem(held) == PotionTypes.WATER)
        {
            if(!world.isRemote && tank != null)
            {
                if(tank.fill(new FluidStack(FluidRegistry.WATER, BOTTLE), false) == BOTTLE)
                {
                    tank.fill(new FluidStack(FluidRegistry.WATER, BOTTLE), true);
                    if(!player.capabilities.isCreativeMode)
                    {
                        player.setHeldItem(hand, new ItemStack(Items.GLASS_BOTTLE));
                    }
                    world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
                    afterChange.run();
                }
            }
            return true;
        }
        if(player.isSneaking() && held.isEmpty())
            return false;
        if(!toggle)
            return false;
        if(world.isRemote)
        {
            splash(world, pos);
            return true;
        }
        if(tank != null)
        {
            toggle(world, pos, player, tank, noSourceKey, sources);
        }
        return true;
    }

    /** 开 / 关（服务端） */
    public static void toggle(World world, BlockPos pos, EntityPlayer player, FurnitureTank tank, String noSourceKey, BlockPos... sources)
    {
        boolean open = !tank.isOpen();
        if(open && tank.isFull())
        {
            player.sendStatusMessage(new TextComponentTranslation("cfm.message.water_full"), true);
            return;
        }
        tank.setOpen(open);
        if(open)
        {
            world.playSound(null, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, FurnitureSounds.tap, SoundCategory.BLOCKS, 0.75F, 1.0F);
            boolean source = false;
            for(BlockPos below : sources)
            {
                source |= hasWaterBelow(world, below);
            }
            player.sendStatusMessage(new TextComponentTranslation(source ? "cfm.message.water_on" : noSourceKey), true);
        }
        else
        {
            player.sendStatusMessage(new TextComponentTranslation("cfm.message.water_off"), true);
        }
    }

    /** 下面这一格（从上面接触）有没有认得的水可抽 */
    public static boolean hasWaterBelow(World world, BlockPos below)
    {
        if(!world.isBlockLoaded(below))
            return false;
        IFluidHandler handler = FluidUtil.getFluidHandler(world, below, EnumFacing.UP);
        return handler != null && com.mrcrayfish.furniture.compat.WaterFluids.drainWater(handler, null, 1, false) != null;
    }

    private static void splash(World world, BlockPos pos)
    {
        for(int i = 0; i < 5; i++)
        {
            world.spawnParticle(EnumParticleTypes.WATER_SPLASH, pos.getX() + 0.3 + 0.4 * RANDOM.nextDouble(), pos.getY() + 1.05, pos.getZ() + 0.3 + 0.4 * RANDOM.nextDouble(), 0, 0, 0);
        }
    }

    /** 浴缸显示用的水位 */
    public static int bathLevel(FurnitureTank tank)
    {
        return WaterRules.bathLevel(tank.getAmount());
    }
}
