package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.compat.RegionUse;
import com.mrcrayfish.furniture.computer.ComputerNetwork;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.PageFacts;
import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.Ipv6Address;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 家具电脑（悠然服务器魔改，服主 11-14）：台式机，内置浏览器，接入 YouranMod 的游戏内 IPv6 网络。
 * <ul>
 * <li>电：界面上有电源开关，开着时 4 FE/t，关着不耗电；只从底面进电，能量单元卡放在界面的卡槽里（见 {@link ApplianceEnergy}）。
 * 没电时黑屏。</li>
 * <li>操作者：同一时刻只有一个玩家操作（第一个打开、且有使用权的人；他关掉界面后别人可以“接管”）。网络协议栈跑在操作者的客户端上，
 * 这台电脑在设备表里登记成它那一格的设备，有电且有人操作时网卡接到操作者那里。</li>
 * <li>旁观：别的玩家打开界面时看操作者当前的页面：操作者上传页面，服务端存在这里（只在内存）、转给旁观者，旁观者本地自己渲染。</li>
 * </ul>
 * 原来的 MineBay 改成了游戏内网站（{@code minebay.yr}），这里不再有交易的槽位；旧存档槽里剩的东西会掉出来。
 */
public class TileEntityComputer extends TileEntityFurniture implements ITickable, IAppliance
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.COMPUTER, ApplianceFaces.bottom(), () -> TileEntityUtil.markBlockForUpdate(world, pos));

    private boolean switchedOn;

    // ---- 只在内存（服务端），操作者同步给客户端 ----
    @Nullable
    private UUID operator;
    private String operatorName = "";
    @Nullable
    private DeviceHandle device;
    /** 当前页面（压缩的 {@code PageSnapshot}）；没有为 null */
    @Nullable
    private byte[] page;
    /** 服务端自己从当前页面解析出来的（图片所在的主机等），不信客户端报的 */
    private PageFacts pageFacts = PageFacts.NONE;
    private int scroll;
    private int retryTicks;
    private int ticks;

    public TileEntityComputer()
    {
        super("computer", 1);
    }

    // ---- 状态 ----

    public boolean isSwitchedOn()
    {
        return switchedOn;
    }

    @Nullable
    public UUID getOperatorId()
    {
        return operator;
    }

    public String getOperatorName()
    {
        return operatorName;
    }

    public boolean isOperator(EntityPlayer player)
    {
        return operator != null && operator.equals(player.getUniqueID());
    }

    @Nullable
    public Ipv6Address deviceAddress()
    {
        DeviceHandle d = device;
        return d == null || d.isRemoved() ? null : d.address();
    }

    @Nullable
    public byte[] getPage()
    {
        return page;
    }

    public int getScroll()
    {
        return scroll;
    }

    public PageFacts getPageFacts()
    {
        return pageFacts;
    }

    /** 当前操作者（服务端；不在线、没开着界面时为 null） */
    @Nullable
    public EntityPlayerMP getOperatorPlayer()
    {
        if(operator == null || world == null || world.isRemote || world.getMinecraftServer() == null)
            return null;
        EntityPlayerMP p = world.getMinecraftServer().getPlayerList().getPlayerByUUID(operator);
        if(p == null || p.world != world || !(p.openContainer instanceof ContainerComputer) || ((ContainerComputer) p.openContainer).getComputer() != this)
            return null;
        return p;
    }

    /** 开着这台电脑界面的玩家（服务端） */
    public List<EntityPlayerMP> getViewers()
    {
        List<EntityPlayerMP> out = new ArrayList<>();
        if(world == null || world.isRemote)
            return out;
        for(EntityPlayer p : world.playerEntities)
        {
            if(p instanceof EntityPlayerMP && p.openContainer instanceof ContainerComputer && ((ContainerComputer) p.openContainer).getComputer() == this)
                out.add((EntityPlayerMP) p);
        }
        return out;
    }

    // ---- 服务端操作（主线程） ----

    /** 有人打开了界面：没人操作且他有使用权时，他成为操作者 */
    public void onOpened(EntityPlayerMP player)
    {
        if(getOperatorPlayer() == null && RegionUse.canUse(world, player, pos))
        {
            setOperator(player);
        }
        ComputerSync.sendView(player, this);
    }

    /** 关掉了界面：操作者走了就放开 */
    public void onClosed(EntityPlayer player)
    {
        if(isOperator(player))
        {
            setOperator(null);
        }
        if(world != null && !world.isRemote)
            ComputerSync.ungrant(player.getUniqueID(), this);
    }

    /** 接管（没人操作时） */
    public boolean claim(EntityPlayerMP player)
    {
        if(getOperatorPlayer() != null || !RegionUse.canUse(world, player, pos))
            return false;
        setOperator(player);
        return true;
    }

    /** 电源开关（操作者） */
    public void togglePower()
    {
        this.switchedOn = !this.switchedOn;
        if(!switchedOn)
        {
            // 关机清屏
            setPage(null, PageFacts.NONE);
        }
        this.markDirty();
        TileEntityUtil.markBlockForUpdate(world, pos);
    }

    public void setPage(@Nullable byte[] compressed, PageFacts facts)
    {
        this.page = compressed;
        this.pageFacts = compressed == null ? PageFacts.NONE : facts;
        this.scroll = 0;
        ComputerSync.broadcastPage(this);
    }

    public void setScroll(int scroll)
    {
        this.scroll = Math.max(0, scroll);
        ComputerSync.broadcastScroll(this);
    }

    private void setOperator(@Nullable EntityPlayerMP player)
    {
        UUID id = player == null ? null : player.getUniqueID();
        if(id == null ? operator == null : id.equals(operator))
            return;
        this.operator = id;
        this.operatorName = player == null ? "" : player.getName();
        if(id != null)
            ComputerSync.ungrant(id, this);
        if(device != null && !device.isRemoved() && player == null && device.attachedPlayer() != null)
        {
            device.detach();
        }
        TileEntityUtil.markBlockForUpdate(world, pos);
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote)
            return;
        boolean running = energy.tick(switchedOn, AppliancePower.COMPUTER);
        if(operator != null && getOperatorPlayer() == null)
        {
            // 操作者下线、死了、换了维度、界面被关掉（含距离太远）
            setOperator(null);
        }
        dropLegacySlot();
        updateDevice(running);
        // 旁观者的放行：每秒查一次谁不看了，每分钟给还在看的续期
        if(++ticks % 20 == 0)
            ComputerSync.refreshSpectators(this, ticks % 1200 == 0);
    }

    /** 设备表：登记（网络开服后才有，失败时每秒再试）、通电、网卡接到操作者 */
    private void updateDevice(boolean running)
    {
        if(device == null || device.isRemoved())
        {
            device = null;
            if(--retryTicks > 0)
                return;
            retryTicks = 20;
            device = ComputerNetwork.register(this);
            if(device == null)
                return;
        }
        if(device.isPowered() != running)
            device.setPowered(running);
        EntityPlayerMP want = running ? getOperatorPlayer() : null;
        EntityPlayerMP now = device.attachedPlayer();
        if(want != now)
        {
            if(want == null)
                device.detach();
            else
                device.attachPlayer(want);
        }
    }

    /** 旧存档 MineBay 交易槽里剩下的东西：掉在电脑上面 */
    private void dropLegacySlot()
    {
        ItemStack stack = this.getStackInSlot(0);
        if(!stack.isEmpty())
        {
            this.setInventorySlotContents(0, ItemStack.EMPTY);
            EntityItem item = new EntityItem(world, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, stack);
            world.spawnEntity(item);
            this.markDirty();
        }
    }

    @Override
    public void invalidate()
    {
        super.invalidate();
        releaseDevice();
    }

    @Override
    public void onChunkUnload()
    {
        super.onChunkUnload();
        releaseDevice();
    }

    private void releaseDevice()
    {
        if(world != null && !world.isRemote)
        {
            ComputerNetwork.unregister(device);
            device = null;
            ComputerSync.ungrantAll(this);
        }
    }

    // ---- 存档与同步 ----

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.switchedOn = tagCompound.getBoolean("PowerOn");
        this.energy.readFromNBT(tagCompound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setBoolean("PowerOn", switchedOn);
        this.energy.writeToNBT(tagCompound);
        return tagCompound;
    }

    private NBTTagCompound syncTag()
    {
        NBTTagCompound tag = this.writeToNBT(new NBTTagCompound());
        if(operator != null)
        {
            tag.setUniqueId("Operator", operator);
            tag.setString("OperatorName", operatorName);
        }
        return tag;
    }

    private void readSync(NBTTagCompound tag)
    {
        this.readFromNBT(tag);
        this.operator = tag.hasUniqueId("Operator") ? tag.getUniqueId("Operator") : null;
        this.operatorName = tag.getString("OperatorName");
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket()
    {
        return new SPacketUpdateTileEntity(pos, getBlockMetadata(), syncTag());
    }

    @Override
    public NBTTagCompound getUpdateTag()
    {
        return syncTag();
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt)
    {
        readSync(pkt.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag)
    {
        readSync(tag);
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        ContainerComputer c = new ContainerComputer(playerInventory, this);
        if(playerIn instanceof EntityPlayerMP)
        {
            onOpened((EntityPlayerMP) playerIn);
        }
        return c;
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
