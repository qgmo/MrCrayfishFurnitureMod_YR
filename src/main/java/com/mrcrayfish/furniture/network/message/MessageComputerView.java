package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.PageSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 → 客户端：电脑当前的页面（旁观用）、清屏、滚动位置（悠然服务器魔改）。
 * 页面是压缩的 {@link PageSnapshot}，客户端渲染前再按上限检查。
 */
public class MessageComputerView implements IMessage, IMessageHandler<MessageComputerView, IMessage>
{
    public static final int PAGE = 0, CLEAR = 1, SCROLL = 2;

    private BlockPos pos = BlockPos.ORIGIN;
    private int kind;
    private int scroll;
    private byte[] data = new byte[0];

    public MessageComputerView() {}

    private MessageComputerView(BlockPos pos, int kind, int scroll, byte[] data)
    {
        this.pos = pos;
        this.kind = kind;
        this.scroll = scroll;
        this.data = data;
    }

    public static MessageComputerView page(BlockPos pos, byte[] page, int scroll)
    {
        return new MessageComputerView(pos, PAGE, scroll, page);
    }

    public static MessageComputerView clear(BlockPos pos)
    {
        return new MessageComputerView(pos, CLEAR, 0, new byte[0]);
    }

    public static MessageComputerView scroll(BlockPos pos, int scroll)
    {
        return new MessageComputerView(pos, SCROLL, scroll, new byte[0]);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.kind = buf.readByte();
        this.scroll = buf.readInt();
        int len = buf.readInt();
        if(len < 0 || len > PageSnapshot.MAX_COMPRESSED || len > buf.readableBytes())
            throw new IllegalArgumentException("computer view too large");
        this.data = new byte[len];
        buf.readBytes(this.data);
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeByte(kind);
        buf.writeInt(scroll);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public IMessage onMessage(MessageComputerView message, MessageContext ctx)
    {
        Minecraft.getMinecraft().addScheduledTask(() -> com.mrcrayfish.furniture.client.browser.ComputerViews.receive(message.pos, message.kind, message.scroll, message.data));
        return null;
    }
}
