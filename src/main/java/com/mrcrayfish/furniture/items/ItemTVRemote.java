package com.mrcrayfish.furniture.items;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.compat.CraftingBatteries;
import com.mrcrayfish.furniture.compat.ItemBatteries;
import com.mrcrayfish.furniture.gui.inventory.InventoryRemoteBattery;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：遥控器装一节电池（合成用的那节连同电量存进遥控器），按一下耗 {@value #FE_PER_PRESS} FE；
 * 没电时只提示。没对着电视时潜行右键打开一格的电池仓换电池。
 */
public class ItemTVRemote extends Item
{
    /** 按一下耗多少 FE */
    public static final int FE_PER_PRESS = 2;

    /** 电池仓界面的编号（GuiHandler） */
    public static final int GUI_ID = 11;

    public ItemTVRemote()
    {
        this.setTranslationKey("tv_remote");
        this.setRegistryName("tv_remote");
        this.setCreativeTab(MrCrayfishFurnitureMod.tabFurniture);
        // 每个遥控器带着自己的电池，不叠放
        this.setMaxStackSize(1);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn)
    {
        ItemStack battery = InventoryRemoteBattery.peek(stack);
        if(battery.isEmpty())
        {
            tooltip.add(TextFormatting.RED + I18n.format("cfm.tv_remote.no_battery"));
        }
        else
        {
            tooltip.add(TextFormatting.GRAY + I18n.format("cfm.tv_remote.charge", ItemBatteries.charge(battery), ItemBatteries.capacity(battery)));
        }
        if(GuiScreen.isShiftKeyDown())
        {
            String info = I18n.format("cfm.tv_remote.info");
            tooltip.addAll(Minecraft.getMinecraft().fontRenderer.listFormattedStringToWidth(info, 150));
        }
        else
        {
            tooltip.add(TextFormatting.YELLOW + I18n.format("cfm.info"));
        }
    }

    /**
     * 合成时（点取或 Shift 点取，产物进背包之前）把合成格里的那节电池连同电量放进遥控器
     */
    @Override
    public void onCreated(ItemStack stack, World worldIn, EntityPlayer playerIn)
    {
        if(InventoryRemoteBattery.peek(stack).isEmpty())
        {
            ItemStack battery = CraftingBatteries.findBattery(playerIn);
            if(!battery.isEmpty())
            {
                InventoryRemoteBattery.store(stack, battery);
            }
        }
    }

    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ, EnumHand hand)
    {
        return activateTelevision(world, player, hand);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn)
    {
        EnumActionResult result = activateTelevision(worldIn, playerIn, handIn);
        if(result == EnumActionResult.PASS && playerIn.isSneaking() && handIn == EnumHand.MAIN_HAND)
        {
            // 没对着电视时潜行右键：打开电池仓
            if(!worldIn.isRemote)
            {
                playerIn.openGui(MrCrayfishFurnitureMod.instance, GUI_ID, worldIn, 0, 0, 0);
            }
            result = EnumActionResult.SUCCESS;
        }
        return new ActionResult<>(result, playerIn.getHeldItem(handIn));
    }

    @Nullable
    private static TileEntityTV findTelevision(World worldIn, EntityPlayer playerIn)
    {
        Vec3d startVec = playerIn.getPositionEyes(0F);
        Vec3d endVec = startVec.add(playerIn.getLookVec().normalize().scale(16));
        RayTraceResult result = worldIn.rayTraceBlocks(startVec, endVec, false);
        if(result != null && result.typeOfHit == RayTraceResult.Type.BLOCK)
        {
            TileEntity tileEntity = worldIn.getTileEntity(result.getBlockPos());
            if(tileEntity instanceof TileEntityTV)
            {
                return (TileEntityTV) tileEntity;
            }
        }
        return null;
    }

    public EnumActionResult activateTelevision(World worldIn, EntityPlayer playerIn, EnumHand hand)
    {
        TileEntityTV tileEntityTV = findTelevision(worldIn, playerIn);
        if(tileEntityTV == null)
            return EnumActionResult.PASS;
        // 换台、开关都只在服务端做，结果同步给客户端
        if(worldIn.isRemote)
            return EnumActionResult.SUCCESS;

        ItemStack remote = playerIn.getHeldItem(hand);
        if(!pressButton(remote))
        {
            playerIn.sendStatusMessage(new TextComponentTranslation(InventoryRemoteBattery.peek(remote).isEmpty() ? "cfm.message.remote_no_battery" : "cfm.message.remote_empty"), true);
            return EnumActionResult.SUCCESS;
        }
        if(playerIn.isSneaking())
        {
            tileEntityTV.setPowered(!tileEntityTV.isPowered());
        }
        else if(tileEntityTV.nextChannel())
        {
            worldIn.playSound(null, tileEntityTV.getPos(), FurnitureSounds.white_noise, SoundCategory.BLOCKS, 0.5F, 1.0F);
        }
        return EnumActionResult.SUCCESS;
    }

    /** 按一下：从电池里取 {@value #FE_PER_PRESS} FE，取到返回 true（电池的新电量写回遥控器） */
    public static boolean pressButton(ItemStack remote)
    {
        if(remote.isEmpty() || !(remote.getItem() instanceof ItemTVRemote))
            return false;
        ItemStack battery = InventoryRemoteBattery.peek(remote);
        if(battery.isEmpty() || !ItemBatteries.drain(battery, FE_PER_PRESS))
            return false;
        InventoryRemoteBattery.store(remote, battery);
        return true;
    }
}
