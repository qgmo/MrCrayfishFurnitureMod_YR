package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.PageLimits;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.PageUploads;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.io.IOException;

/**
 * 客户端 → 服务端：操作者当前页面的一块（悠然服务器魔改）。压缩后的 {@link PageSnapshot} 按 {@link PageUploads#CHUNK} 字节分块。
 * 服务端只限制大小（页面 64 KiB、2000 个元素、64 层）和频率，不看内容；收齐后存进电脑、转给旁观者。
 */
public class MessageComputerPage implements IMessage, IMessageHandler<MessageComputerPage, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private int id;
    private int index;
    private int count;
    private byte[] data = new byte[0];
    private boolean bad;

    public MessageComputerPage() {}

    public MessageComputerPage(BlockPos pos, int id, int index, int count, byte[] data)
    {
        this.pos = pos;
        this.id = id;
        this.index = index;
        this.count = count;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.id = buf.readInt();
        this.index = buf.readShort();
        this.count = buf.readShort();
        int len = buf.readInt();
        if(len < 0 || len > PageUploads.CHUNK || len > buf.readableBytes())
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
        buf.writeInt(id);
        buf.writeShort(index);
        buf.writeShort(count);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public IMessage onMessage(MessageComputerPage message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerPage message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer page chunk too large");
            ComputerSync.UPLOADS.forget(player.getUniqueID());
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, true, "computer page upload");
        if(computer == null)
            return;
        PageUploads.Outcome o = ComputerSync.UPLOADS.accept(player.getUniqueID(), message.pos, message.id, message.index, message.count, message.data, System.currentTimeMillis());
        if(o == PageUploads.Outcome.BAD_CHUNK || o == PageUploads.Outcome.RATE_LIMITED)
        {
            RejectLog.log(player, "computer page upload " + o + " at " + message.pos);
            return;
        }
        if(o != PageUploads.Outcome.COMPLETE)
            return;
        byte[] compressed = ComputerSync.UPLOADS.take(player.getUniqueID());
        if(compressed == null)
            return;
        PageSnapshot snapshot;
        try
        {
            snapshot = PageSnapshot.decode(compressed);
        }
        catch(IOException e)
        {
            RejectLog.log(player, "computer page rejected (" + e.getMessage() + ") at " + message.pos);
            return;
        }
        PageLimits.Problem p = PageLimits.check(snapshot.html);
        if(p != null)
        {
            RejectLog.log(player, "computer page rejected (" + p + ") at " + message.pos);
            return;
        }
        computer.setPage(compressed);
    }
}
