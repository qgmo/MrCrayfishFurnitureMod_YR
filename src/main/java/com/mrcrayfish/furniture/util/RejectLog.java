package com.mrcrayfish.furniture.util;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.entity.player.EntityPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端拒绝客户端请求时记一条日志（悠然服务器魔改）。
 * 同一玩家 10 秒内只记一条，防止改过的客户端连续发包刷日志。只在主线程调用。
 */
public final class RejectLog
{
    private static final long INTERVAL_MS = 10_000L;
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private RejectLog() {}

    public static void log(EntityPlayer player, String what)
    {
        long now = System.currentTimeMillis();
        Long last = LAST.get(player.getUniqueID());
        if(last != null && now - last < INTERVAL_MS)
            return;
        LAST.put(player.getUniqueID(), now);
        if(LAST.size() > 1000)
            LAST.values().removeIf(t -> now - t >= INTERVAL_MS);
        MrCrayfishFurnitureMod.logger().warn("Rejected from {}: {}", player.getName(), what);
    }
}
