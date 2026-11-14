package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.BrowserHistory;
import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：操作者浏览器的当前地址与前进后退历史（只有网址，不含网页内容），存进电脑方块实体，服务器重启后用来恢复（悠然服务器魔改，服主 11-14）。
 * 服务端查：是当前操作者、距离、有电，按玩家限频；条数、长度、每条网址都按 {@link BrowserHistory#checked} 检查，不合格的整条拒收。
 */
public class MessageComputerHistory implements IMessage, IMessageHandler<MessageComputerHistory, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private byte[] data = new byte[0];
    private boolean bad;

    public MessageComputerHistory() {}

    public MessageComputerHistory(BlockPos pos, BrowserHistory history)
    {
        this.pos = pos;
        this.data = history.encode();
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        int len = buf.readInt();
        if(len < 0 || len > BrowserHistory.MAX_ENCODED || len > buf.readableBytes())
        {
            // 不在 Netty 线程上抛异常断人，交给主线程记日志
            this.bad = true;
            buf.skipBytes(buf.readableBytes());
            return;
        }
        this.data = new byte[len];
        buf.readBytes(this.data);
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public IMessage onMessage(MessageComputerHistory message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerHistory message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer history too large");
            return;
        }
        if(!ComputerSync.allowHistory(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer history too fast");
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, true, "computer history");
        if(computer == null)
            return;
        BrowserHistory h = BrowserHistory.decode(message.data);
        if(h == null)
        {
            RejectLog.log(player, "computer history rejected (bad entries) at " + message.pos);
            return;
        }
        computer.setHistory(h);
    }
}
