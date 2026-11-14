package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

/**
 * 客户端 → 服务端：操作者正在打的字（地址栏或表单文本框里还没提交的内容），转给旁观者看（悠然服务器魔改，服主 11-14）。
 * 服务端查：是当前操作者、距离、有电，按玩家限频；最多 {@value TypingSync#MAX_CHARS} 个字符；表单的框要是服务端解析的当前页面上的文本框；
 * 密码框只收长度（带了文字的拒收）。见 {@link TypingSync}。
 */
public class MessageComputerTyping implements IMessage, IMessageHandler<MessageComputerTyping, IMessage>
{
    /** 文字的字节数上限（UTF-8 一个字符最多 3 字节，代理对 4 字节算两个字符） */
    static final int MAX_BYTES = TypingSync.MAX_CHARS * 3;

    private BlockPos pos = BlockPos.ORIGIN;
    private int page;
    private int field;
    private int length;
    private String text = "";
    private boolean bad;

    public MessageComputerTyping() {}

    public MessageComputerTyping(BlockPos pos, int page, TypingSync.Typed t)
    {
        this.pos = pos;
        this.page = page;
        this.field = t.field;
        this.length = t.length;
        this.text = t.text;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.page = buf.readInt();
        this.field = buf.readInt();
        this.length = buf.readUnsignedShort();
        int n = buf.readUnsignedShort();
        if(n > MAX_BYTES || n > buf.readableBytes())
        {
            // 不在 Netty 线程上抛异常断人，交给主线程记日志
            this.bad = true;
            buf.skipBytes(buf.readableBytes());
            return;
        }
        byte[] b = new byte[n];
        buf.readBytes(b);
        this.text = new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        buf.writeLong(pos.toLong());
        buf.writeInt(page);
        buf.writeInt(field);
        buf.writeShort(length);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    @Override
    public IMessage onMessage(MessageComputerTyping message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerTyping message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer typing too large");
            return;
        }
        if(!ComputerSync.allowTyping(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer typing too fast");
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, true, "computer typing");
        if(computer == null)
            return;
        String[] reason = new String[1];
        TypingSync.Typed t = TypingSync.accept(computer.getPageFacts(), computer.getPageId(), message.page, message.field, message.length, message.text, reason);
        if(t == null)
        {
            RejectLog.log(player, "computer " + reason[0] + " at " + message.pos);
            return;
        }
        computer.setTyped(t);
    }
}
