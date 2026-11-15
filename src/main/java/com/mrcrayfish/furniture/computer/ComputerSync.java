package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerView;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.PlayerAddresses;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 旁观同步（服务端，主线程）：操作者上传的页面存在电脑里，转给开着这台电脑界面的其他玩家（旁观者）；滚动位置也转。
 * 服务端不过滤内容，只限制大小（见 {@link PageLimits}、{@link PageUploads}）。
 */
public final class ComputerSync
{
    public static final PageUploads UPLOADS = new PageUploads();
    /** 每个玩家报滚动位置的频率 */
    private static final Map<UUID, RateLimiter> SCROLL = new HashMap<>();
    /** 每个玩家按电源、接管的频率 */
    private static final Map<UUID, RateLimiter> ACTIONS = new HashMap<>();
    /** 每个玩家同步打字的频率 */
    private static final Map<UUID, RateLimiter> TYPING = new HashMap<>();
    /** 每个玩家报会话（前进后退历史）的频率 */
    private static final Map<UUID, RateLimiter> HISTORY = new HashMap<>();
    /** 每个玩家同步画板的频率 */
    private static final Map<UUID, RateLimiter> CANVAS = new HashMap<>();
    /** 每个玩家报 Cookie 的频率 */
    private static final Map<UUID, RateLimiter> COOKIES = new HashMap<>();
    /** 旁观者的玩家临时地址放行页面里图片所在主机的用途（服主 11-14：旁观者自己下载图片） */
    public static final String SPECTATE_PURPOSE = "cfm:spectate";
    /** 放行的有效期；看着的时候每分钟续一次 */
    static final long GRANT_TTL_MS = 5 * 60_000L;
    /** 旁观者 → 他正在看的电脑（维度:位置）；撤销时只撤自己这台给的 */
    private static final Map<UUID, String> SPECTATING = new HashMap<>();

    private ComputerSync() {}

    public static boolean allowScroll(EntityPlayerMP player, long now)
    {
        return SCROLL.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(8, 8)).tryAcquire(now, 1);
    }

    public static boolean allowTyping(EntityPlayerMP player, long now)
    {
        return TYPING.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(TypingSync.SERVER_RATE, TypingSync.SERVER_BURST)).tryAcquire(now, 1);
    }

    public static boolean allowCanvas(EntityPlayerMP player, long now)
    {
        return CANVAS.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(CanvasSync.SERVER_RATE, CanvasSync.SERVER_BURST)).tryAcquire(now, 1);
    }

    /** 画板同步给旁观者的数据：[宽 2][高 2][压缩像素] */
    public static byte[] canvasData(int width, int height, byte[] data)
    {
        byte[] v = new byte[4 + data.length];
        v[0] = (byte) (width >> 8);
        v[1] = (byte) width;
        v[2] = (byte) (height >> 8);
        v[3] = (byte) height;
        System.arraycopy(data, 0, v, 4, data.length);
        return v;
    }

    /** 操作者画的转给旁观者 */
    public static void broadcastCanvas(TileEntityComputer computer, int field, byte[] data)
    {
        for(EntityPlayerMP p : computer.getViewers())
        {
            if(!computer.isOperator(p))
                PacketHandler.INSTANCE.sendTo(MessageComputerView.canvas(computer.getPos(), field, data), p);
        }
    }

    /** 操作者：发主页设置（服务器默认 + 这台电脑的；服主 11-15） */
    public static void sendHome(EntityPlayerMP player, TileEntityComputer computer)
    {
        PacketHandler.INSTANCE.sendTo(MessageComputerView.home(computer.getPos(), HomePage.encode(serverHome(), computer.getHomePage())), player);
    }

    private static String cachedHomeConfig;
    private static String cachedHome;

    /** 配置里的默认主页（规范化过；写得不对时 about:home，记一次日志） */
    public static synchronized String serverHome()
    {
        String c = com.mrcrayfish.furniture.handler.ConfigurationHandler.computerHomePage;
        if(cachedHome == null || !String.valueOf(c).equals(cachedHomeConfig))
        {
            String[] problem = new String[1];
            cachedHome = HomePage.serverDefault(c, problem);
            cachedHomeConfig = String.valueOf(c);
            if(problem[0] != null)
                com.mrcrayfish.furniture.MrCrayfishFurnitureMod.logger().warn("{}; using about:home", problem[0]);
        }
        return cachedHome;
    }

    public static boolean allowHistory(EntityPlayerMP player, long now)
    {
        return HISTORY.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(4, 8)).tryAcquire(now, 1);
    }

    public static boolean allowCookies(EntityPlayerMP player, long now)
    {
        return COOKIES.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(8, 16)).tryAcquire(now, 1);
    }

    /**
     * 操作者：发这台电脑的 Cookie（服主 11-15：Cookie 存在服务端、跟着电脑走；协议栈在操作者客户端上，所以交给他用）。
     * 只在他成为操作者、清除 Cookie 时发；旁观者拿不到。过期时刻按“还剩多久”发，客户端按自己的时钟算。
     */
    public static void sendCookies(@Nullable EntityPlayerMP player, TileEntityComputer computer)
    {
        if(player == null || !computer.isOperator(player))
            return;
        CookieJar jar = ComputerCookies.get(computer);
        PacketHandler.INSTANCE.sendTo(MessageComputerView.cookies(computer.getPos(), jar.encode(false, System.currentTimeMillis())), player);
    }

    /** 操作者：发存着的会话，他的浏览器没有这台电脑的状态时（重启后、换了人）按它恢复 */
    public static void sendHistory(EntityPlayerMP player, TileEntityComputer computer)
    {
        if(!computer.getHistory().isEmpty())
            PacketHandler.INSTANCE.sendTo(MessageComputerView.history(computer.getPos(), computer.getHistory()), player);
    }

    public static boolean allowAction(EntityPlayerMP player, long now)
    {
        return ACTIONS.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(4, 4)).tryAcquire(now, 1);
    }

    /** 刚打开界面的人：发当前页面；旁观者顺带放行页面里的图片所在的主机（先放行再发页面，旁观者开始取图时已经放行了） */
    public static void sendView(EntityPlayerMP player, TileEntityComputer computer)
    {
        if(!computer.isOperator(player))
            grant(player, computer);
        else
        {
            sendHome(player, computer);
            sendHistory(player, computer);
        }
        byte[] page = computer.getPage();
        PacketHandler.INSTANCE.sendTo(page == null ? MessageComputerView.clear(computer.getPos())
                : MessageComputerView.page(computer.getPos(), page, computer.getScroll()), player);
        if(!computer.isOperator(player))
        {
            for(TypingSync.Typed t : computer.getTyped())
                PacketHandler.INSTANCE.sendTo(MessageComputerView.typing(computer.getPos(), t), player);
            for(Map.Entry<Integer, byte[]> c : computer.getCanvases())
                PacketHandler.INSTANCE.sendTo(MessageComputerView.canvas(computer.getPos(), c.getKey(), c.getValue()), player);
        }
    }

    /** 操作者打的字转给旁观者 */
    public static void broadcastTyping(TileEntityComputer computer, TypingSync.Typed t)
    {
        for(EntityPlayerMP p : computer.getViewers())
        {
            if(!computer.isOperator(p))
                PacketHandler.INSTANCE.sendTo(MessageComputerView.typing(computer.getPos(), t), p);
        }
    }

    private static String key(TileEntityComputer computer)
    {
        return computer.getWorld().provider.getDimension() + ":" + computer.getPos().toLong();
    }

    /** 旁观者正在看这台电脑：按服务端自己解析出的页面放行（没有图时撤销） */
    public static void grant(EntityPlayerMP player, TileEntityComputer computer)
    {
        SPECTATING.put(player.getUniqueID(), key(computer));
        Set<Ipv6Address> hosts = computer.getPageFacts().imageHosts;
        if(hosts.isEmpty())
            PlayerAddresses.revoke(player, SPECTATE_PURPOSE);
        else
            PlayerAddresses.allow(player, SPECTATE_PURPOSE, hosts, GRANT_TTL_MS);
    }

    /** 不看这台电脑了（关界面、成了操作者）：撤销 */
    public static void ungrant(UUID player, TileEntityComputer computer)
    {
        String k = SPECTATING.get(player);
        if(k != null && k.equals(key(computer)))
        {
            SPECTATING.remove(player);
            PlayerAddresses.revoke(player, SPECTATE_PURPOSE);
        }
    }

    /**
     * 每秒一次（电脑的 tick 里）：撤销已经不看这台电脑的人的放行；{@code renew} 时给还在看的续期。
     */
    public static void refreshSpectators(TileEntityComputer computer, boolean renew)
    {
        String k = key(computer);
        Set<UUID> watching = new HashSet<>();
        for(EntityPlayerMP p : computer.getViewers())
        {
            if(computer.isOperator(p))
                continue;
            watching.add(p.getUniqueID());
            if(renew || !k.equals(SPECTATING.get(p.getUniqueID())))
                grant(p, computer);
        }
        Iterator<Map.Entry<UUID, String>> it = SPECTATING.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<UUID, String> e = it.next();
            if(e.getValue().equals(k) && !watching.contains(e.getKey()))
            {
                it.remove();
                PlayerAddresses.revoke(e.getKey(), SPECTATE_PURPOSE);
            }
        }
    }

    /** 电脑没了（拆掉、区块卸载）：撤销所有看它的人的放行 */
    public static void ungrantAll(TileEntityComputer computer)
    {
        String k = key(computer);
        Iterator<Map.Entry<UUID, String>> it = SPECTATING.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<UUID, String> e = it.next();
            if(e.getValue().equals(k))
            {
                it.remove();
                PlayerAddresses.revoke(e.getKey(), SPECTATE_PURPOSE);
            }
        }
    }

    public static void broadcastPage(TileEntityComputer computer)
    {
        for(EntityPlayerMP p : computer.getViewers())
        {
            if(!computer.isOperator(p))
                sendView(p, computer);
        }
    }

    public static void broadcastScroll(TileEntityComputer computer)
    {
        for(EntityPlayerMP p : computer.getViewers())
        {
            if(!computer.isOperator(p))
                PacketHandler.INSTANCE.sendTo(MessageComputerView.scroll(computer.getPos(), computer.getScroll()), p);
        }
    }

    /** 下线时清掉这个玩家的上传状态 */
    public static final class Events
    {
        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
        {
            UPLOADS.forget(event.player.getUniqueID());
            SCROLL.remove(event.player.getUniqueID());
            ACTIONS.remove(event.player.getUniqueID());
            TYPING.remove(event.player.getUniqueID());
            HISTORY.remove(event.player.getUniqueID());
            CANVAS.remove(event.player.getUniqueID());
            COOKIES.remove(event.player.getUniqueID());
            // 临时地址下线就收回了，放行跟着没了
            SPECTATING.remove(event.player.getUniqueID());
        }
    }
}
