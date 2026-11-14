package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerView;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.PlayerAddresses;

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

    public static boolean allowAction(EntityPlayerMP player, long now)
    {
        return ACTIONS.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(4, 4)).tryAcquire(now, 1);
    }

    /** 刚打开界面的人：发当前页面；旁观者顺带放行页面里的图片所在的主机（先放行再发页面，旁观者开始取图时已经放行了） */
    public static void sendView(EntityPlayerMP player, TileEntityComputer computer)
    {
        if(!computer.isOperator(player))
            grant(player, computer);
        byte[] page = computer.getPage();
        PacketHandler.INSTANCE.sendTo(page == null ? MessageComputerView.clear(computer.getPos())
                : MessageComputerView.page(computer.getPos(), page, computer.getScroll()), player);
        if(!computer.isOperator(player))
        {
            for(TypingSync.Typed t : computer.getTyped())
                PacketHandler.INSTANCE.sendTo(MessageComputerView.typing(computer.getPos(), t), player);
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
            // 临时地址下线就收回了，放行跟着没了
            SPECTATING.remove(event.player.getUniqueID());
        }
    }
}
