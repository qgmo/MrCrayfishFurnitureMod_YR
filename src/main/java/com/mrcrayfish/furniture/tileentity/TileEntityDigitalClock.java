package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.compat.ClockBatteries;
import com.mrcrayfish.furniture.util.ClockPower;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.util.Constants;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：要装一节电池才走。每 2 分钟耗 1 FE；本期的电费付得起（{@link #isPowered()}）屏幕才亮。
 * 方块实体在区块加载时才 tick，所以只在加载时耗电。电池随方块实体存档；旧存档里的时钟没有电池，屏幕黑着。
 */
public class TileEntityDigitalClock extends TileEntityColored implements ITickable
{
    /** 耗电间隔（tick）；系统属性 cfm.clockDrainTicks 只给测试调快 */
    private static final int INTERVAL = ClockPower.parseInterval(System.getProperty("cfm.clockDrainTicks"));

    private EnumDyeColor textColor = EnumDyeColor.WHITE;
    private ItemStack battery = ItemStack.EMPTY;
    /** 本期的电费已经付了（屏幕亮着）；会同步给客户端 */
    private boolean powered;
    private final ClockPower.DrainTimer timer = new ClockPower.DrainTimer(INTERVAL);

    @Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);
        if(compound.hasKey("textColor", Constants.NBT.TAG_BYTE))
        {
            this.textColor = EnumDyeColor.byMetadata(compound.getByte("textColor"));
        }
        this.battery = compound.hasKey("battery", Constants.NBT.TAG_COMPOUND) ? new ItemStack(compound.getCompoundTag("battery")) : ItemStack.EMPTY;
        this.powered = !battery.isEmpty() && compound.getBoolean("powered");
        this.timer.setRemaining(compound.hasKey("drainTicks", Constants.NBT.TAG_INT) ? compound.getInteger("drainTicks") : INTERVAL);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);
        compound.setByte("textColor", (byte) textColor.getMetadata());
        if(!battery.isEmpty())
        {
            compound.setTag("battery", battery.writeToNBT(new NBTTagCompound()));
        }
        compound.setBoolean("powered", powered);
        compound.setInteger("drainTicks", timer.getRemaining());
        return compound;
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote || !powered)
            return;
        if(timer.tick())
        {
            // 这一期到头了：再付下一期的电费，付不起屏幕就黑
            if(!ClockBatteries.drain(battery))
            {
                this.powered = false;
                this.sync();
            }
            this.markDirty();
        }
    }

    public EnumDyeColor getTextColor()
    {
        return textColor;
    }

    public void setTextColorColor(EnumDyeColor color)
    {
        this.textColor = color;
        this.sync();
    }

    public ItemStack getBattery()
    {
        return battery;
    }

    public boolean hasBattery()
    {
        return !battery.isEmpty();
    }

    /** 屏幕亮着 */
    public boolean isPowered()
    {
        return powered;
    }

    /**
     * 放入电池（只在服务端调），返回原来的电池（没有则空）。上一期的电费还付着就接着用，否则新电池先付一期。
     */
    public ItemStack swapBattery(ItemStack newBattery)
    {
        ItemStack old = this.battery;
        this.battery = newBattery;
        if(!this.powered)
        {
            this.powered = ClockBatteries.drain(newBattery);
            this.timer.reset();
        }
        this.markDirty();
        this.sync();
        return old;
    }

    /** 取出电池（只在服务端调），返回取出的电池（没有则空）；屏幕随即黑掉 */
    public ItemStack removeBattery()
    {
        ItemStack old = this.battery;
        this.battery = ItemStack.EMPTY;
        this.powered = false;
        this.timer.reset();
        this.markDirty();
        this.sync();
        return old;
    }
}
