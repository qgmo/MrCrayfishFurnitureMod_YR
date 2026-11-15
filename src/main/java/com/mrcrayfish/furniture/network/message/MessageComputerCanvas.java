package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.CanvasSync;
import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.PixelCanvas;
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
 * 客户端 → 服务端：操作者在画板里画的（整张，压缩），转给旁观者看（悠然服务器魔改，服主 11-15）。
 * 服务端查：是当前操作者、距离、有电，按玩家限频；画板要是服务端解析的当前页面上的、尺寸对得上、解出来正好宽 × 高个合法像素。见 {@link CanvasSync}。
 */
public class MessageComputerCanvas implements IMessage, IMessageHandler<MessageComputerCanvas, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private int page;
    private int field;
    private int width;
    private int height;
    private byte[] data = new byte[0];
    private boolean bad;

    public MessageComputerCanvas() {}

    public MessageComputerCanvas(BlockPos pos, int page, int field, PixelCanvas canvas)
    {
        this.pos = pos;
        this.page = page;
        this.field = field;
        this.width = canvas.width;
        this.height = canvas.height;
        this.data = canvas.syncBytes();
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.page = buf.readInt();
        this.field = buf.readInt();
        this.width = buf.readUnsignedShort();
        this.height = buf.readUnsignedShort();
        int len = buf.readInt();
        if(len < 0 || len > PixelCanvas.MAX_SYNC_BYTES || len > buf.readableBytes())
        {
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
        buf.writeInt(page);
        buf.writeInt(field);
        buf.writeShort(width);
        buf.writeShort(height);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public IMessage onMessage(MessageComputerCanvas message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerCanvas message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer canvas too large");
            return;
        }
        if(!ComputerSync.allowCanvas(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer canvas too fast");
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, true, "computer canvas");
        if(computer == null)
            return;
        String reason = CanvasSync.check(computer.getPageFacts(), computer.getPageId(), message.page, message.field, message.width, message.height, message.data);
        if(reason != null)
        {
            RejectLog.log(player, "computer " + reason + " at " + message.pos);
            return;
        }
        computer.setCanvas(message.field, message.width, message.height, message.data);
    }
}
