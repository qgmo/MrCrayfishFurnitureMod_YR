package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import javax.annotation.Nullable;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：要用电，转时 1 FE/t，没电停转；只从上面进电。没有界面：拿能量单元卡潜行 + 左键放入，
 * 再潜行 + 左键（空手）取出（见 {@link ApplianceEnergy}）。
 */
public class TileEntityCeilingFan extends TileEntitySyncClient implements ITickable, IAppliance
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.CEILING_FAN, ApplianceFaces.top(), this::sync);

    private boolean powered = false;
    private float maxSpeed = 30F;
    private float acceleration = 0.25F;

    @SideOnly(Side.CLIENT)
    public float prevFanRotation;
    @SideOnly(Side.CLIENT)
    public float fanRotation;
    @SideOnly(Side.CLIENT)
    private float currentSpeed;

    public void setPowered(boolean powered)
    {
        this.powered = powered;
        this.markDirty();
        TileEntityUtil.syncToClient(this);
    }

    public boolean isPowered()
    {
        return powered;
    }

    @Override
    public void update()
    {
        if(!world.isRemote)
        {
            energy.tick(powered, AppliancePower.CEILING_FAN);
            return;
        }
        if(world.isRemote)
        {
            prevFanRotation = fanRotation;

            if(powered && energy.isRunning())
            {
                currentSpeed += acceleration;
                if(currentSpeed > maxSpeed)
                {
                    currentSpeed = maxSpeed;
                }
            }
            else
            {
                currentSpeed *= 0.95F;
            }

           fanRotation += currentSpeed;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);
        if(compound.hasKey("maxSpeed", Constants.NBT.TAG_FLOAT))
        {
            maxSpeed = compound.getFloat("maxSpeed");
        }
        if(compound.hasKey("acceleration", Constants.NBT.TAG_FLOAT))
        {
            acceleration = compound.getFloat("acceleration");
        }
        if(compound.hasKey("powered", Constants.NBT.TAG_BYTE))
        {
            this.powered = compound.getBoolean("powered");
        }
        this.energy.readFromNBT(compound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);
        compound.setBoolean("powered", powered);
        compound.setFloat("maxSpeed", maxSpeed);
        compound.setFloat("acceleration", acceleration);
        this.energy.writeToNBT(compound);
        return compound;
    }

    @Override
    public double getMaxRenderDistanceSquared()
    {
        return 16384;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox()
    {
        return INFINITE_EXTENT_AABB;
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
