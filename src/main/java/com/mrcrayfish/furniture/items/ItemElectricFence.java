package com.mrcrayfish.furniture.items;

import com.mrcrayfish.furniture.compat.CraftingBatteries;
import com.mrcrayfish.furniture.compat.ItemBatteries;
import com.mrcrayfish.furniture.tileentity.TileEntityElectricFence;
import com.mrcrayfish.furniture.util.FencePower;
import net.minecraft.block.Block;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 电栅栏的物品（悠然服务器魔改）：NBT 里记容量与电量（{@link TileEntityElectricFence#TAG_CAPACITY}、
 * {@link TileEntityElectricFence#TAG_ENERGY}）。NBT 不同就不叠在一起，所以不同容量、不同电量的电栅栏各占一格。
 */
public class ItemElectricFence extends ItemBlock
{
    public ItemElectricFence(Block block)
    {
        super(block);
    }

    /**
     * 合成时（点取或 Shift 点取，产物进背包之前）：容量与电量取合成格里那节电池的
     */
    @Override
    public void onCreated(ItemStack stack, World worldIn, EntityPlayer playerIn)
    {
        NBTTagCompound tag = stack.getTagCompound();
        if(tag != null && tag.hasKey(TileEntityElectricFence.TAG_CAPACITY))
            return;
        ItemStack battery = CraftingBatteries.findBattery(playerIn);
        if(battery.isEmpty())
            return;
        int capacity = ItemBatteries.capacity(battery);
        if(capacity <= 0)
            return;
        TileEntityElectricFence.writeItemTag(stack, FencePower.sanitizeEnergy(ItemBatteries.charge(battery), capacity), capacity);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn)
    {
        NBTTagCompound tag = stack.getTagCompound();
        int capacity = FencePower.sanitizeCapacity(tag != null ? tag.getInteger(TileEntityElectricFence.TAG_CAPACITY) : 0);
        int energy = FencePower.sanitizeEnergy(tag != null ? tag.getInteger(TileEntityElectricFence.TAG_ENERGY) : 0, capacity);
        tooltip.add(TextFormatting.GRAY + I18n.format("cfm.electric_fence.charge", energy, capacity));
    }
}
