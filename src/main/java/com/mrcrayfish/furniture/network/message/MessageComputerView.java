package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.BrowserHistory;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.TypingSync;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * 服务端 → 客户端：电脑当前的页面（旁观用）、清屏、滚动位置、操作者正在打的字；只发给操作者的会话与 Cookie（悠然服务器魔改）。
 * 页面是压缩的 {@link PageSnapshot}，客户端渲染前再按上限检查。
 */
public class MessageComputerView implements IMessage, IMessageHandler<MessageComputerView, IMessage>
{
    public static final int PAGE = 0, CLEAR = 1, SCROLL = 2, TYPING = 3, HISTORY = 4, COOKIES = 5, CANVAS = 6, HOME = 7;

    private BlockPos pos = BlockPos.ORIGIN;
    private int kind;
    private int scroll;
    private byte[] data = new byte[0];
    /** 页面的上传编号（只有 {@link #PAGE} 带；旁观者传屏幕缩略图时按它对上页面） */
    private int id = -1;

    public MessageComputerView() {}

    private MessageComputerView(BlockPos pos, int kind, int scroll, byte[] data)
    {
        this.pos = pos;
        this.kind = kind;
        this.scroll = scroll;
        this.data = data;
    }

    public static MessageComputerView page(BlockPos pos, byte[] page, int scroll, int id)
    {
        MessageComputerView m = new MessageComputerView(pos, PAGE, scroll, page);
        m.id = id;
        return m;
    }

    public static MessageComputerView clear(BlockPos pos)
    {
        return new MessageComputerView(pos, CLEAR, 0, new byte[0]);
    }

    public static MessageComputerView scroll(BlockPos pos, int scroll)
    {
        return new MessageComputerView(pos, SCROLL, scroll, new byte[0]);
    }

    /** 操作者打的字：{@code scroll} 那一格放框的编号，数据是 [是不是密码框 1][长度 2][文字 UTF-8] */
    public static MessageComputerView typing(BlockPos pos, TypingSync.Typed t)
    {
        byte[] text = t.text.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[3 + text.length];
        data[0] = (byte) (t.password ? 1 : 0);
        data[1] = (byte) (t.length >> 8);
        data[2] = (byte) t.length;
        System.arraycopy(text, 0, data, 3, text.length);
        return new MessageComputerView(pos, TYPING, t.field, data);
    }

    /** 存着的会话（只发给操作者，给他的浏览器恢复用） */
    public static MessageComputerView history(BlockPos pos, BrowserHistory h)
    {
        return new MessageComputerView(pos, HISTORY, 0, h.encode());
    }

    /** 这台电脑的 Cookie（只发给操作者；{@link com.mrcrayfish.furniture.computer.CookieJar#encode} 的“还剩多久”格式） */
    public static MessageComputerView cookies(BlockPos pos, byte[] jar)
    {
        return new MessageComputerView(pos, COOKIES, 0, jar);
    }

    /** 操作者画板里画的：{@code scroll} 那一格放画板的编号，数据是 [宽 2][高 2][压缩像素] */
    public static MessageComputerView canvas(BlockPos pos, int field, byte[] data)
    {
        return new MessageComputerView(pos, CANVAS, field, data);
    }

    /** 主页设置（只发给操作者；{@link com.mrcrayfish.furniture.computer.HomePage#encode}） */
    public static MessageComputerView home(BlockPos pos, byte[] data)
    {
        return new MessageComputerView(pos, HOME, 0, data);
    }

    /** 客户端：解出打的字；格式不对返回 null */
    @Nullable
    public static TypingSync.Typed decodeTyping(int field, byte[] data)
    {
        if(data.length < 3 || data.length > 3 + TypingSync.MAX_CHARS * 3)
            return null;
        boolean password = data[0] != 0;
        int length = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        String text = new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        if(length > TypingSync.MAX_LENGTH || text.length() > TypingSync.MAX_CHARS || (password && !text.isEmpty()))
            return null;
        return new TypingSync.Typed(field, password, length, text);
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
        if(kind == PAGE)
            this.id = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeByte(kind);
        buf.writeInt(scroll);
        buf.writeInt(data.length);
        buf.writeBytes(data);
        if(kind == PAGE)
            buf.writeInt(id);
    }

    @Override
    public IMessage onMessage(MessageComputerView message, MessageContext ctx)
    {
        Minecraft.getMinecraft().addScheduledTask(() -> com.mrcrayfish.furniture.client.browser.ComputerViews.receive(message.pos, message.kind, message.scroll, message.id, message.data));
        return null;
    }
}
