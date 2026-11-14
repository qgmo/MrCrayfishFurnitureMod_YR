package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.gui.containers.ContainerMicrowave;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.util.ParticleSpawner;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;

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
 * 悠然服务器魔改：要用电，加热时 12 FE/t，没电暂停；只从底面进电，可插能量单元卡（见 {@link ApplianceEnergy}）。
 */
public class TileEntityMicrowave extends TileEntityFurniture implements ISidedInventory, ITickable, IAppliance
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.MICROWAVE, ApplianceFaces.bottom(), () -> TileEntityUtil.markBlockForUpdate(world, pos));

    private Random rand = new Random();

    private static final int[] slot = new int[]{0};

    private String customName;
    private boolean cooking = false;
    public int progress = 0;
    private int timer = 0;

    public TileEntityMicrowave()
    {
        super("microwave", 1);
    }

    public ItemStack getItem()
    {
        return getStackInSlot(0);
    }

    public void startCooking()
    {
        if(!getStackInSlot(0).isEmpty())
        {
            RecipeData data = RecipeAPI.getMicrowaveRecipeFromIngredients(getStackInSlot(0));
            if(data != null)
            {
                cooking = true;
                world.updateComparatorOutputLevel(pos, blockType);
            }
        }
    }

    public void stopCooking()
    {
        this.cooking = false;
        this.progress = 0;
        world.updateComparatorOutputLevel(pos, blockType);
    }

    public boolean isCooking()
    {
        return cooking;
    }

    @Override
    public void update()
    {
        // 没电时暂停（进度不动、不响）
        if(!energy.tick(cooking, AppliancePower.MICROWAVE) && cooking)
        {
            return;
        }
        if(cooking)
        {
            if(this.world.isRemote)
            {
                double posX = pos.getX() + 0.35D + (rand.nextDouble() / 3);
                double posZ = pos.getZ() + 0.35D + (rand.nextDouble() / 3);
                ParticleSpawner.spawnParticle("smoke", posX, pos.getY() + 0.065D, posZ);
            }

            progress++;
            if(progress >= 40)
            {
                if(!getStackInSlot(0).isEmpty())
                {
                    RecipeData data = RecipeAPI.getMicrowaveRecipeFromIngredients(getStackInSlot(0));
                    if(data != null)
                    {
                        this.setInventorySlotContents(0, data.getOutput().copy());
                    }
                }
                if(world.isRemote)
                {
                    world.playSound(pos.getX(), pos.getY(), pos.getZ(), FurnitureSounds.microwave_finish, SoundCategory.BLOCKS, 0.75F, 1.0F, true);
                }
                timer = 0;
                progress = 0;
                cooking = false;
                world.updateComparatorOutputLevel(pos, blockType);
            }
            else
            {
                if(timer == 20)
                {
                    timer = 0;
                }
                if(timer == 0)
                {
                    world.playSound(pos.getX(), pos.getY(), pos.getZ(), FurnitureSounds.microwave_running, SoundCategory.BLOCKS, 0.75F, 1.0F, true);
                }
                timer++;
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.cooking = tagCompound.getBoolean("Coooking");
        this.progress = tagCompound.getInteger("Progress");
        this.energy.readFromNBT(tagCompound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setBoolean("Coooking", cooking);
        tagCompound.setInteger("Progress", progress);
        this.energy.writeToNBT(tagCompound);
        return tagCompound;
    }

    @Override
    public int getInventoryStackLimit()
    {
        return 1;
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack)
    {
        return RecipeAPI.getMicrowaveRecipeFromIngredients(stack) != null;
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side)
    {
        return slot;
    }

    @Override
    public boolean canInsertItem(int index, ItemStack stack, EnumFacing direction)
    {
        return RecipeAPI.getMicrowaveRecipeFromIngredients(stack) != null;
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, EnumFacing direction)
    {
        return RecipeAPI.getMicrowaveRecipeFromIngredients(stack) == null;
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot(playerIn);
        return new ContainerMicrowave(playerInventory, this);
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
