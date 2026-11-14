package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.api.Recipes;
import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.nbt.NBTTagCompound;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import javax.annotation.Nullable;
import net.minecraft.util.ITickable;

/**
 * 悠然服务器魔改：要用电，开机（有人开着界面）时 4 FE/t；没电时界面黑屏，槽里的东西照旧留着。只从底面进电，
 * 可插能量单元卡（潜行 + 左键，见 {@link ApplianceEnergy}）。
 */
public class TileEntityComputer extends TileEntityFurniture implements ITickable, IAppliance
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.COMPUTER, ApplianceFaces.bottom(), () -> TileEntityUtil.markBlockForUpdate(world, pos));

    private int stockNum = 0;
    private boolean isTrading = false;

    public TileEntityComputer()
    {
        super("computer", 1);
    }

    public void takeEmeraldFromSlot(int price)
    {
        this.getStackInSlot(0).shrink(price);
        this.markDirty();
    }

    public void setBrowsingInfo(int stockNum)
    {
        if(stockNum >= 0 && stockNum < Recipes.getMineBayItems().length)
        {
            this.stockNum = stockNum;
        }
    }

    public int getBrowsingInfo()
    {
        return stockNum;
    }

    public void setTrading(boolean isUsing)
    {
        this.isTrading = isUsing;
    }

    public boolean isTrading()
    {
        return isTrading;
    }


    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.stockNum = tagCompound.getInteger("StockNum");
        this.energy.readFromNBT(tagCompound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setInteger("StockNum", stockNum);
        this.energy.writeToNBT(tagCompound);
        return tagCompound;
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote)
            return;
        energy.tick(this.isInUse(), AppliancePower.COMPUTER);
    }

    /** 开机：有玩家正开着这台电脑的界面（不靠 isTrading：玩家掉线时它不会清） */
    private boolean isInUse()
    {
        for(EntityPlayer player : world.playerEntities)
        {
            if(player.openContainer instanceof ContainerComputer && ((ContainerComputer) player.openContainer).getComputerInventory() == this)
                return true;
        }
        return false;
    }

    @Override
    public void openInventory(EntityPlayer player)
    {
        setTrading(true);
    }

    @Override
    public void closeInventory(EntityPlayer player)
    {
        setTrading(false);
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot(playerIn);
        return new ContainerComputer(playerInventory, this);
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
