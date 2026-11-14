package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerView;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
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

    private ComputerSync() {}

    public static boolean allowScroll(EntityPlayerMP player, long now)
    {
        return SCROLL.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(8, 8)).tryAcquire(now, 1);
    }

    /** 刚打开界面的人：发当前页面 */
    public static void sendView(EntityPlayerMP player, TileEntityComputer computer)
    {
        byte[] page = computer.getPage();
        PacketHandler.INSTANCE.sendTo(page == null ? MessageComputerView.clear(computer.getPos())
                : MessageComputerView.page(computer.getPos(), page, computer.getScroll()), player);
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
        }
    }
}
