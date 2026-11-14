package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.api.RecipeAPI;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.api.Recipes;
import com.mrcrayfish.furniture.gui.containers.ContainerFreezer;
import com.mrcrayfish.furniture.init.FurnitureItems;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import javax.annotation.Nullable;

/**
 * 悠然服务器魔改：冰箱（冷冻柜 + 上面的冷藏室算一台）要用电。一直耗 1 FE/t，冷冻时再加 8 FE/t；没电时冷冻暂停，
 * 冷藏室照样能存取。电从冷冻柜底面、背面或冷藏室背面进，能量单元卡插在冷冻柜界面里（见 {@link ApplianceEnergy}）。
 */
public class TileEntityFreezer extends TileEntityFurniture implements ISidedInventory, ITickable, IAppliance
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.FRIDGE + AppliancePower.FREEZING, ApplianceFaces.bottomAndBack(this), () -> TileEntityUtil.markBlockForUpdate(world, pos));

    private static final int[] slots_bottom = new int[]{2};
    private static final int[] slots_sides = new int[]{0, 1};

    private boolean freezing = false;
    public int progress = 0;
    public int timeRemaining = 0;
    public int fuelTime = 0;

    public TileEntityFreezer()
    {
        super("freezer", 3);
    }

    public void startFreezing()
    {
        if(canFreeze())
        {
            if(timeRemaining == 0)
            {
                fuelTime = getFuelTime(inventory.get(0));
                timeRemaining = fuelTime;

                inventory.get(0).shrink(1);
                if(inventory.get(0).getCount() <= 0)
                {
                    removeStackFromSlot(0);
                }
            }
            freezing = true;
            world.updateComparatorOutputLevel(pos, blockType);
        }
    }

    public void stopFreezing()
    {
        freezing = false;
        world.updateComparatorOutputLevel(pos, blockType);
    }

    public boolean canFreeze()
    {
        if(inventory.get(0) == ItemStack.EMPTY && timeRemaining == 0)
        {
            return false;
        }

        if(inventory.get(0) != ItemStack.EMPTY && timeRemaining == 0)
        {
            if(!isFuel(inventory.get(0)))
            {
                return false;
            }
        }

        if(inventory.get(1) != ItemStack.EMPTY)
        {
            RecipeData data = Recipes.getFreezerRecipeFromInput(inventory.get(1));

            if(data == null)
            {
                return false;
            }

            if(inventory.get(2) == ItemStack.EMPTY)
            {
                return true;
            }

            if(inventory.get(2).getItem() != data.getOutput().getItem())
            {
                return false;
            }

            if(inventory.get(2).getCount() < this.getInventoryStackLimit() && inventory.get(2).getCount() < inventory.get(2).getMaxStackSize())
            {
                return true;
            }
        }
        return false;
    }

    public void freezeItem()
    {
        if(inventory.get(1) != ItemStack.EMPTY)
        {
            RecipeData data = Recipes.getFreezerRecipeFromInput(inventory.get(1));

            if(data == null)
            {
                return;
            }

            if(inventory.get(2) == ItemStack.EMPTY)
            {
                inventory.set(2, data.getOutput().copy());
            }
            else if(inventory.get(2).getItem() == data.getOutput().getItem() && inventory.get(2).getItemDamage() == data.getOutput().getItemDamage())
            {
                inventory.get(2).grow(data.getOutput().copy().getCount());
            }

            if(inventory.get(1).getItem().hasContainerItem())
            {
                inventory.set(1, new ItemStack(inventory.get(1).getItem().getContainerItem()));
            }
            else
            {
                inventory.get(1).shrink(1);
            }

            if(inventory.get(1).getCount() <= 0)
            {
                removeStackFromSlot(1);
            }
        }
    }

    public boolean isFreezing()
    {
        return freezing;
    }

    public static boolean isFuel(ItemStack stack)
    {
        return getFuelTime(stack) > 0;
    }

    private static int getFuelTime(ItemStack stack)
    {
        if(stack == null) return 0;
        if(stack.getItem() == Item.getItemFromBlock(Blocks.PACKED_ICE)) return 3000;
        if(stack.getItem() == Item.getItemFromBlock(Blocks.ICE)) return 2000;
        if(stack.getItem() == FurnitureItems.COOL_PACK) return 400;
        return 0;
    }

    @Override
    public void update()
    {
        boolean freezePowered = this.payPower();
        if(freezing)
        {
            if(!canFreeze())
            {
                freezing = false;
                world.updateComparatorOutputLevel(pos, blockType);
                return;
            }

            // 没电时冷冻暂停：进度、剩余冰量都不动
            if(!freezePowered)
            {
                return;
            }

            progress++;
            if(progress >= 200)
            {
                freezeItem();
                progress = 0;
            }

            timeRemaining--;
            if(timeRemaining <= 0)
            {
                if(inventory.get(0) != ItemStack.EMPTY && isFuel(inventory.get(0)))
                {
                    fuelTime = getFuelTime(inventory.get(0));
                    timeRemaining = fuelTime;

                    inventory.get(0).shrink(1);
                    if(inventory.get(0).getCount() <= 0)
                    {
                        removeStackFromSlot(0);
                    }
                }
                else
                {
                    timeRemaining = 0;
                    freezing = false;
                    world.updateComparatorOutputLevel(pos, blockType);
                }
            }
        }
        else
        {
            if(progress > 0)
            {
                progress--;
            }
        }
    }


    /**
     * 付这一 tick 的电（服务端）：一直耗 1 FE/t，冷冻时一共 9 FE/t（付不起冷冻那份时，常耗的 1 FE 照付）。
     * 返回这一 tick 冷冻有没有电；客户端返回同步来的状态。
     */
    private boolean payPower()
    {
        if(world.isRemote)
            return energy.isRunning();
        boolean freezeOk = false;
        if(freezing && canFreeze())
        {
            freezeOk = energy.draw(AppliancePower.FRIDGE + AppliancePower.FREEZING);
        }
        if(!freezeOk)
        {
            energy.draw(AppliancePower.FRIDGE);
        }
        energy.setRunning(freezeOk);
        return freezeOk;
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.freezing = tagCompound.getBoolean("Freezing");
        this.progress = tagCompound.getInteger("Progress");
        this.fuelTime = tagCompound.getInteger("FuelTime");
        this.timeRemaining = tagCompound.getInteger("Remaining");
        this.energy.readFromNBT(tagCompound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setBoolean("Freezing", freezing);
        tagCompound.setInteger("Progress", progress);
        tagCompound.setInteger("FuelTime", fuelTime);
        tagCompound.setInteger("Remaining", timeRemaining);
        this.energy.writeToNBT(tagCompound);
        return tagCompound;
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side)
    {
        if(side == EnumFacing.DOWN) return slots_bottom;
        return slots_sides;
    }

    @Override
    public boolean canInsertItem(int slotIn, ItemStack stack, EnumFacing side)
    {
        if(isLocked()) return false;

        if(side != EnumFacing.DOWN)
        {
            if(slotIn == 0) return isFuel(stack);
            if(slotIn == 1) return RecipeAPI.getFreezerRecipeFromInput(stack) != null;
        }
        return false;
    }

    @Override
    public boolean canExtractItem(int slotId, ItemStack stack, EnumFacing side)
    {
        return side == EnumFacing.DOWN && !isLocked();
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot(playerIn);
        return new ContainerFreezer(playerInventory, this);
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
