package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.compat.RegionUse;
import com.mrcrayfish.furniture.computer.BrowserHistory;
import com.mrcrayfish.furniture.computer.ComputerCookies;
import com.mrcrayfish.furniture.computer.ComputerCpu;
import com.mrcrayfish.furniture.computer.ComputerNetwork;
import com.mrcrayfish.furniture.computer.ComputerPeripherals;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.PageFacts;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.client.browser.PageDocument;
import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import moe.qingu.youran.api.network.DeviceCapability;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>
 * 主板（服主 11-16）：合成时从家具电脑主板带进来的芯片数据（{@value ComputerCpu#TAG_BOARD}），一片 YR-2000 决定浏览器的处理速度
 * （{@link ComputerCpu}）。进存档、随方块更新发给客户端（浏览器在操作者的客户端上），拆下时随掉落物走。没有的（以前放下的、创造模式拿的）
 * 按标准 2.0 GHz。
 */
public class TileEntityComputer extends TileEntityFurniture implements ITickable, IAppliance, moe.qingu.youran.api.peripheral.PeripheralHost
{
    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.COMPUTER, ApplianceFaces.bottom(), () -> TileEntityUtil.markBlockForUpdate(world, pos));

    private boolean switchedOn;
    /** 外设槽（定位模块；服主 11-15）：进存档，也随方块更新发给客户端（界面里显示） */
    private final ComputerPeripherals peripherals = new ComputerPeripherals(this::onPeripheralsChanged);
    /** 会话：当前地址与前进后退历史（进存档，不存网页内容；服主 11-14） */
    private BrowserHistory history = BrowserHistory.EMPTY;
    /** 这台电脑的 Cookie 在 {@link ComputerCookies} 里的编号（第一次存 Cookie 时生成；进存档，不发给客户端） */
    @Nullable
    private UUID cookieJarId;
    /** 主板的数据（芯片品质；服主 11-16）；没有为 null（按标准频率） */
    @Nullable
    private NBTTagCompound board;
    /** 这台电脑的主页（玩家“设为这台电脑的主页”；没设为 null，用服务器默认；服主 11-15）。进存档，只发给操作者 */
    @Nullable
    private String homePage;

    // ---- 只在内存（服务端），操作者同步给客户端 ----
    @Nullable
    private UUID operator;
    private String operatorName = "";
    /** 排版的基准（服务端配置，随方块更新发给客户端；客户端收到前用默认值） */
    private int layoutBaseMs = ComputerCpu.DEFAULT_LAYOUT_BASE_MS;
    private int layoutPerKbMs = ComputerCpu.DEFAULT_LAYOUT_PER_KB_MS;
    @Nullable
    private DeviceHandle device;
    /** 当前页面（压缩的 {@code PageSnapshot}）；没有为 null */
    @Nullable
    private byte[] page;
    /** 服务端自己从当前页面解析出来的（图片所在的主机等），不信客户端报的 */
    private PageFacts pageFacts = PageFacts.NONE;
    /** 当前页面是操作者的第几次上传（打字同步按它对上页面） */
    private int pageId = -1;
    /** 操作者正在打的字（每个框最新的一条；换页面清空），给新来的旁观者补发 */
    private final Map<Integer, TypingSync.Typed> typed = new LinkedHashMap<>();
    /** 操作者画板里画的（每个画板最新的一张：[宽 2][高 2][压缩像素]；换页面清空），给新来的旁观者补发 */
    private final Map<Integer, byte[]> canvases = new LinkedHashMap<>();
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

    public int getPageId()
    {
        return pageId;
    }

    /** YouranMod 外设接口：装着的外设（服务端） */
    @Override
    public java.util.List<ItemStack> installedPeripherals()
    {
        return peripherals.installed();
    }

    /** 家具电脑什么外设都能插（服主 11-16），只有定位模块起作用（见 {@link ComputerPeripherals}） */
    @Override
    public boolean acceptsPeripheral(String kind)
    {
        return kind != null && !kind.isEmpty();
    }

    /** 主板的数据（没有为 null） */
    @Nullable
    public NBTTagCompound getBoard()
    {
        return board;
    }

    /** CPU 实际跑的频率（kHz；坏了为 0） */
    public int cpuClockKHz()
    {
        return ComputerCpu.clockKHz(board);
    }

    /** 这一页（多少字节）在这台电脑上比标准频率多等多久排版（毫秒；客户端用服务端发来的基准） */
    public long extraLayoutMillis(int pageBytes)
    {
        int base = world != null && !world.isRemote ? com.mrcrayfish.furniture.handler.ConfigurationHandler.computerLayoutBaseMs : layoutBaseMs;
        int perKb = world != null && !world.isRemote ? com.mrcrayfish.furniture.handler.ConfigurationHandler.computerLayoutPerKbMs : layoutPerKbMs;
        return ComputerCpu.extraLayoutMillis(pageBytes, cpuClockKHz(), base, perKb);
    }

    /** CPU 坏了：通了电也没有反应 */
    public boolean isCpuDead()
    {
        return ComputerCpu.dead(board);
    }

    public ComputerPeripherals getPeripherals()
    {
        return peripherals;
    }

    /**
     * 能不能放、拿外设：是现在的操作者，且过保护检查（同接管电脑的 {@link RegionUse#canUse}）。
     * 客户端上按同步来的操作者判断（只用来少发不会被接受的点击），服务端说了算。
     */
    public boolean canChangePeripherals(EntityPlayer player)
    {
        return world != null && isOperator(player) && RegionUse.canUse(world, player, pos);
    }

    private void onPeripheralsChanged()
    {
        if(world != null && !world.isRemote)
        {
            markDirty();
            DeviceHandle d = device;
            if(d != null && !d.isRemoved())
                d.setCapability(DeviceCapability.LOCATION, peripherals.hasLocator());
            TileEntityUtil.markBlockForUpdate(world, pos);
        }
    }

    /** 拆电脑时：外设掉出来 */
    public void dropPeripherals()
    {
        for(int i = 0; i < ComputerPeripherals.SLOTS; i++)
        {
            ItemStack s = peripherals.get(i);
            if(!s.isEmpty())
            {
                peripherals.set(i, ItemStack.EMPTY);
                net.minecraft.inventory.InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), s);
            }
        }
    }

    public BrowserHistory getHistory()
    {
        return history;
    }

    /** 操作者报来的会话（已经检查过） */
    public void setHistory(BrowserHistory h)
    {
        if(!h.equals(history))
        {
            this.history = h;
            this.markDirty();
        }
    }

    @Nullable
    public UUID getCookieJarId()
    {
        return cookieJarId;
    }

    public UUID getOrCreateCookieJarId()
    {
        if(cookieJarId == null)
        {
            cookieJarId = UUID.randomUUID();
            markDirty();
        }
        return cookieJarId;
    }

    public Collection<TypingSync.Typed> getTyped()
    {
        return typed.values();
    }

    public Collection<Map.Entry<Integer, byte[]>> getCanvases()
    {
        return canvases.entrySet();
    }

    /** 操作者画的（已经检查过）：记下、转给旁观者 */
    public void setCanvas(int field, int width, int height, byte[] data)
    {
        if(!canvases.containsKey(field) && canvases.size() >= PageDocument.MAX_CONTROLS)
            return;
        byte[] v = ComputerSync.canvasData(width, height, data);
        canvases.put(field, v);
        ComputerSync.broadcastCanvas(this, field, v);
    }

    @Nullable
    public String getHomePage()
    {
        return homePage;
    }

    /** 这台电脑的主页（检查过的网址；null 为用服务器默认） */
    public void setHomePage(@Nullable String url)
    {
        this.homePage = url;
        this.markDirty();
    }

    /** 操作者打的字（已经检查过）：记下、转给旁观者 */
    public void setTyped(TypingSync.Typed t)
    {
        if(!typed.containsKey(t.field) && typed.size() >= PageDocument.MAX_CONTROLS + 1)
            return;
        typed.put(t.field, t);
        ComputerSync.broadcastTyping(this, t);
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
        ComputerSync.sendHome(player, this);
        ComputerSync.sendHistory(player, this);
        return true;
    }

    /** 电源开关（操作者） */
    public void togglePower()
    {
        this.switchedOn = !this.switchedOn;
        if(!switchedOn)
        {
            // 关机清屏
            setPage(null, PageFacts.NONE, -1);
        }
        this.markDirty();
        TileEntityUtil.markBlockForUpdate(world, pos);
    }

    public void setPage(@Nullable byte[] compressed, PageFacts facts, int id)
    {
        this.page = compressed;
        this.pageFacts = compressed == null ? PageFacts.NONE : facts;
        this.pageId = compressed == null ? -1 : id;
        this.typed.clear();
        this.canvases.clear();
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
        {
            ComputerSync.ungrant(id, this);
            // Cookie 只交给操作者（协议栈在他的客户端上），旁观者拿不到（服主 11-15）
            ComputerSync.sendCookies(player, this);
        }
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
        // CPU 坏了：照样耗电，但电脑没有反应，网卡不上电
        updateDevice(running && !isCpuDead());
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
        // 网卡附带一键登录能力（服主 11-15）；装了定位模块时有位置能力
        if(!device.hasCapability(DeviceCapability.ONE_CLICK_LOGIN))
            device.setCapability(DeviceCapability.ONE_CLICK_LOGIN, true);
        boolean locator = peripherals.hasLocator();
        if(device.hasCapability(DeviceCapability.LOCATION) != locator)
            device.setCapability(DeviceCapability.LOCATION, locator);
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
        this.history = BrowserHistory.readFromNBT(tagCompound);
        this.cookieJarId = tagCompound.hasUniqueId("CookieJarId") ? tagCompound.getUniqueId("CookieJarId") : null;
        this.peripherals.readFromNBT(tagCompound);
        this.board = ComputerCpu.board(tagCompound);
        if(board != null)
            board = board.copy();
        String home = tagCompound.getString("HomePage");
        // 被改过的存档：不合格的当作没设
        this.homePage = com.mrcrayfish.furniture.computer.HomePage.isValid(home) ? home : null;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        tagCompound.setBoolean("PowerOn", switchedOn);
        this.energy.writeToNBT(tagCompound);
        this.history.writeToNBT(tagCompound);
        if(cookieJarId != null)
            tagCompound.setUniqueId("CookieJarId", cookieJarId);
        this.peripherals.writeToNBT(tagCompound);
        if(board != null)
            tagCompound.setTag(ComputerCpu.TAG_BOARD, board.copy());
        if(homePage != null)
            tagCompound.setString("HomePage", homePage);
        return tagCompound;
    }

    private NBTTagCompound syncTag()
    {
        NBTTagCompound tag = stripPrivate(this.writeToNBT(new NBTTagCompound()));
        if(operator != null)
        {
            tag.setUniqueId("Operator", operator);
            tag.setString("OperatorName", operatorName);
        }
        tag.setInteger("LayoutBaseMs", com.mrcrayfish.furniture.handler.ConfigurationHandler.computerLayoutBaseMs);
        tag.setInteger("LayoutPerKbMs", com.mrcrayfish.furniture.handler.ConfigurationHandler.computerLayoutPerKbMs);
        return tag;
    }

    /**
     * 方块更新发给附近所有人的 NBT 里去掉只给操作者的东西：会话（打开界面时单独发给操作者）、Cookie 的编号（Cookie 本身不在方块实体里，
     * 只在服务端存档，只交给操作者；服主 11-15）。
     */
    public static NBTTagCompound stripPrivate(NBTTagCompound tag)
    {
        tag.removeTag("History");
        tag.removeTag("HistoryIndex");
        tag.removeTag("CookieJarIdMost");
        tag.removeTag("CookieJarIdLeast");
        tag.removeTag("HomePage");
        return tag;
    }

    private void readSync(NBTTagCompound tag)
    {
        this.readFromNBT(tag);
        this.cookieJarId = null;
        this.operator = tag.hasUniqueId("Operator") ? tag.getUniqueId("Operator") : null;
        this.operatorName = tag.getString("OperatorName");
        if(tag.hasKey("LayoutBaseMs"))
            this.layoutBaseMs = tag.getInteger("LayoutBaseMs");
        if(tag.hasKey("LayoutPerKbMs"))
            this.layoutPerKbMs = tag.getInteger("LayoutPerKbMs");
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
