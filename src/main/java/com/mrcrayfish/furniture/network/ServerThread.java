package com.mrcrayfish.furniture.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.function.Consumer;

/**
 * 客户端发来的包在 Netty 线程上收到，这里把处理（含全部校验）转到服务端主线程执行（悠然服务器魔改）。
 * 原来的处理器直接在 Netty 线程读写世界、方块实体和背包，与主线程的 tick 存在竞态；
 * 成就触发在 SpongeForge 上还会抛异常把发包人踢下线。
 */
public final class ServerThread
{
    private ServerThread() {}

    public static void run(MessageContext ctx, Consumer<EntityPlayerMP> task)
    {
        NetHandlerPlayServer handler = ctx.getServerHandler();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if(server == null)
            return;
        server.addScheduledTask(() ->
        {
            // 执行时再取玩家：重生后 NetHandler 指向新的玩家实体
            EntityPlayerMP player = handler.player;
            if(player != null && player.connection == handler)
            {
                task.accept(player);
            }
        });
    }
}
