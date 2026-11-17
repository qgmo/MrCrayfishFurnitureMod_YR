package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.compat.RegionUse;
import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.RateLimiter;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端 → 服务端：家具电脑的 CPU（悠然服务器魔改，服主 11-17）。都只收现在的操作者的：
 * <ul>
 * <li>{@link #UP} / {@link #DOWN}：电源开关旁边的调频旋钮拧一格（过保护检查，同电源开关）；</li>
 * <li>{@link #LOAD}：浏览器这一秒忙的比例（千分比，取页面、排版的时间），服务端拿去算发热；</li>
 * <li>{@link #CRASH}：排版时出了“死机”那种运行错误（服务端核对这片 CPU 现在确实会出错才收）。</li>
 * </ul>
 */
public class MessageComputerCpu implements IMessage, IMessageHandler<MessageComputerCpu, IMessage>
{
    public static final int UP = 0, DOWN = 1, LOAD = 2, CRASH = 3;
    private static final Map<UUID, RateLimiter> LIMIT = new HashMap<>();

    private BlockPos pos = BlockPos.ORIGIN;
    private int action;
    private int value;

    public MessageComputerCpu() {}

    public MessageComputerCpu(BlockPos pos, int action, int value)
    {
        this.pos = pos;
        this.action = action;
        this.value = value;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.value = buf.readShort();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        buf.writeShort(value);
    }

    @Override
    public IMessage onMessage(MessageComputerCpu message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerCpu message, EntityPlayerMP player)
    {
        // 旋钮可以用滚轮连着拧，负载每秒一条：每人每秒最多 12 条
        if(!LIMIT.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(12, 12)).tryAcquire(System.currentTimeMillis(), 1))
        {
            RejectLog.log(player, "computer cpu messages too fast at " + message.pos);
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, false, "computer cpu");
        if(computer == null)
            return;
        switch(message.action)
        {
            case UP:
            case DOWN:
                if(!RegionUse.canUse(player.world, player, message.pos))
                {
                    RejectLog.log(player, "computer clock knob without permission at " + message.pos);
                    return;
                }
                computer.turnKnob(player, message.action == UP ? 1 : -1);
                break;
            case LOAD:
                computer.reportLoad(Math.max(0, Math.min(1000, message.value)));
                break;
            case CRASH:
                if(!computer.reportCrash(player))
                    RejectLog.log(player, "computer crash report while the cpu cannot fault at " + message.pos);
                break;
            default:
                RejectLog.log(player, "unknown computer cpu action " + message.action);
        }
    }

    /** 下线时丢掉限频状态 */
    public static void forget(UUID player)
    {
        LIMIT.remove(player);
    }
}
