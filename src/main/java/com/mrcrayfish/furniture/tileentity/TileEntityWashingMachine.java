package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.containers.ContainerWashingMachine;
import com.mrcrayfish.furniture.init.FurnitureItems;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageUpdateFields;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;

import java.util.Random;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import javax.annotation.Nullable;

/**
 * 悠然服务器魔改：要用电，洗时 6 FE/t，没电暂停；只从底面进电，可插能量单元卡（见 {@link ApplianceEnergy}）。修装备照旧。
 */
public class TileEntityWashingMachine extends TileEntityFurniture implements ISidedInventory, ITickable, IAppliance
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.WASHING_MACHINE, ApplianceFaces.bottom(), () -> TileEntityUtil.markBlockForUpdate(world, pos));

    private static final int[] slots_top = new int[]{0, 1, 2, 3};
    private static final int[] slots_bottom = new int[]{0, 1, 2, 3, 4};
    private static final int[] slots_sides = new int[]{4};

    private boolean washing = false;
    public boolean superMode = false;
    public int progress = 0;
    public int timeRemaining = 0;

    public TileEntityWashingMachine()
    {
        super("washing_machine", 5);
    }

    public void startWashing()
    {
        if(canWash())
        {
            if(timeRemaining == 0)
            {
                superMode = getStackInSlot(4).getItem() == FurnitureItems.SUPER_SOAPY_WATER;
                setInventorySlotContents(4, new ItemStack(getStackInSlot(4).getItem().getContainerItem()));
                timeRemaining = 5000;
            }
            washing = true;
            world.updateComparatorOutputLevel(pos, blockType);
        }
    }

    public void stopWashing()
    {
        progress = 0;
        washing = false;
        world.updateComparatorOutputLevel(pos, blockType);
    }

    public boolean canWash()
    {
        if(getStackInSlot(4).isEmpty() && timeRemaining == 0)
        {
            return false;
        }

        if(!getStackInSlot(4).isEmpty() && timeRemaining == 0)
        {
            if(getStackInSlot(4).getItem() != FurnitureItems.SOAPY_WATER)
            {
                if(getStackInSlot(4).getItem() != FurnitureItems.SUPER_SOAPY_WATER)
                {
                    return false;
                }
            }
        }

        for(int i = 0; i < 4; i++)
        {
            if(!getStackInSlot(i).isEmpty())
            {
                RecipeData data = RecipeAPI.getWashingMachineRecipeFromInput(getStackInSlot(i));
                if(data != null)
                {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isWashing()
    {
        return washing;
    }

    public static boolean isFuel(ItemStack stack)
    {
        if(stack == null) return false;
        if(stack.getItem() == FurnitureItems.SOAPY_WATER) return true;
        if(stack.getItem() == FurnitureItems.SUPER_SOAPY_WATER) return true;
        return false;
    }

    private Random rand = new Random();
    private int timer = 0;

    @Override
    public void update()
    {
        // 没电时暂停（进度、剩余时间都不动）
        if(!energy.tick(washing, AppliancePower.WASHING_MACHINE) && washing)
        {
            return;
        }
        if(washing)
        {
            if(world.isRemote)
            {
                progress++;
                return;
            }

            if(!canWash())
            {
                washing = false;
                world.updateComparatorOutputLevel(pos, blockType);
                this.markDirty();
                return;
            }

            if(canRepair())
            {
                for(int i = 0; i < 4; i++)
                {
                    if(!getStackInSlot(i).isEmpty())
                    {
                        if(getStackInSlot(i).getMaxDamage() - getStackInSlot(i).getItemDamage() != getStackInSlot(i).getMaxDamage())
                        {
                            getStackInSlot(i).setItemDamage(getStackInSlot(i).getItemDamage() - 1);
                        }
                    }
                }
                PacketHandler.INSTANCE.sendToAllAround(new MessageUpdateFields(this, pos), new TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 32));
                if(progress >= 360)
                {
                    progress = 0;
                }
            }

            timeRemaining--;
            if(timeRemaining <= 0)
            {
                if(!getStackInSlot(4).isEmpty())
                {
                    if(getStackInSlot(4).getItem() == FurnitureItems.SOAPY_WATER)
                    {
                        this.superMode = false;
                        setInventorySlotContents(4, new ItemStack(FurnitureItems.SOAPY_WATER.getContainerItem()));
                        timeRemaining = 5000;
                    }
                    else if(getStackInSlot(4).getItem() == FurnitureItems.SUPER_SOAPY_WATER)
                    {
                        this.superMode = true;
                        setInventorySlotContents(4, new ItemStack(FurnitureItems.SUPER_SOAPY_WATER.getContainerItem()));
                        timeRemaining = 5000;
                    }
                }
                else
                {
                    timeRemaining = 0;
                    progress = 0;
                    washing = false;
                    world.updateComparatorOutputLevel(pos, blockType);
                }
                this.markDirty();
            }

            progress++;

            if(timer == 20)
            {
                timer = 0;
            }
            if(timer == 0)
            {
                world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, FurnitureSounds.washing_machine, SoundCategory.BLOCKS, 0.75F, 1.0F);
            }
            timer++;
        }
    }

    public boolean canRepair()
    {
        return progress % (superMode ? 20 : 50) == 0;
    }

    @Override
    public int getInventoryStackLimit()
    {
        return 1;
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.washing = tagCompound.getBoolean("Washing");
        this.superMode = tagCompound.getBoolean("SuperMode");
        this.progress = tagCompound.getInteger("Progress");
        this.timeRemaining = tagCompound.getInteger("Remaining");
        this.energy.readFromNBT(tagCompound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setBoolean("Washing", washing);
        tagCompound.setBoolean("SuperMode", superMode);
        tagCompound.setInteger("Progress", progress);
        tagCompound.setInteger("Remaining", timeRemaining);
        this.energy.writeToNBT(tagCompound);
        return tagCompound;
    }

    @Override
    public int getField(int id)
    {
        switch(id)
        {
            case 0:
                return this.progress;
            case 1:
                return this.timeRemaining;
        }
        return 0;
    }

    @Override
    public void setField(int id, int value)
    {
        switch(id)
        {
            case 0:
                this.progress = value;
            case 1:
                this.timeRemaining = value;
        }
    }

    @Override
    public int getFieldCount()
    {
        return 2;
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack)
    {
        if(stack.getItem() instanceof ItemArmor)
        {
            ItemArmor armour = (ItemArmor) stack.getItem();
            return slot == armour.armorType.getIndex();
        }
        return true;
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side)
    {
        if(side == EnumFacing.UP) return slots_top;
        if(side == EnumFacing.DOWN) return slots_bottom;
        return slots_sides;
    }

    @Override
    public boolean canInsertItem(int index, ItemStack stack, EnumFacing side)
    {
        if(isLocked())
        {
            return false;
        }
        if(side == EnumFacing.UP)
        {
            return RecipeAPI.getWashingMachineRecipeFromInput(stack) != null;
        }
        return side != EnumFacing.DOWN && isFuel(stack);
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, EnumFacing side)
    {
        return side == EnumFacing.DOWN && !isFuel(stack) && stack.getItemDamage() == 0 && !isLocked();
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot(playerIn);
        return new ContainerWashingMachine(playerInventory, this);
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
            return energy.isInputFace(facing);
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
            return energy.isInputFace(facing) ? CapabilityEnergy.ENERGY.cast(energy.port()) : null;
        return super.getCapability(capability, facing);
    }

    @Override
    public ApplianceEnergy getAppliance()
    {
        return energy;
    }
}
