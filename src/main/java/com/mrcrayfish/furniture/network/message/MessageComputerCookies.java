package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerCookies;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.CookieJar;
import com.mrcrayfish.furniture.computer.UrlInput;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：操作者的浏览器收到的 {@code Set-Cookie}（原样，带上那次请求的网址），服务端按同样的规则存进这台电脑的 Cookie
 * （悠然服务器魔改，服主 11-15：Cookie 存在服务端、跟着电脑走；协议栈在操作者客户端上，响应里的新 Cookie 由它交回来）。
 * 服务端查：是当前操作者、距离、有电，按玩家限频；网址要是 http / https，条数、长度有上限。
 */
public class MessageComputerCookies implements IMessage, IMessageHandler<MessageComputerCookies, IMessage>
{
    /** 一次最多交几条 */
    public static final int MAX_LINES = 16;
    /** 一条最多多少字节（名字加值 4096 + 属性） */
    public static final int MAX_LINE_BYTES = 8192;

    private BlockPos pos = BlockPos.ORIGIN;
    private String url = "";
    private List<String> lines = new ArrayList<>();
    private boolean bad;

    public MessageComputerCookies() {}

    public MessageComputerCookies(BlockPos pos, String url, List<String> setCookies)
    {
        this.pos = pos;
        this.url = url;
        this.lines = new ArrayList<>(setCookies.subList(0, Math.min(MAX_LINES, setCookies.size())));
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        String u = read(buf, UrlInput.MAX_LENGTH * 4);
        int n = u == null ? -1 : buf.readUnsignedByte();
        if(u == null || n > MAX_LINES)
        {
            fail(buf);
            return;
        }
        this.url = u;
        for(int i = 0; i < n; i++)
        {
            String l = read(buf, MAX_LINE_BYTES);
            if(l == null)
            {
                fail(buf);
                return;
            }
            lines.add(l);
        }
    }

    /** 不在 Netty 线程上抛异常断人，交给主线程记日志 */
    private void fail(ByteBuf buf)
    {
        this.bad = true;
        this.lines.clear();
        buf.skipBytes(buf.readableBytes());
    }

    private static String read(ByteBuf buf, int max)
    {
        if(buf.readableBytes() < 2)
            return null;
        int n = buf.readUnsignedShort();
        if(n > max || n > buf.readableBytes())
            return null;
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void write(ByteBuf buf, String s)
    {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        write(buf, url);
        buf.writeByte(lines.size());
        for(String l : lines)
            write(buf, l);
    }

    @Override
    public IMessage onMessage(MessageComputerCookies message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerCookies message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer cookies too large");
            return;
        }
        if(!ComputerSync.allowCookies(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer cookies too fast");
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, true, "computer cookies");
        if(computer == null)
            return;
        UrlInput.Result r = UrlInput.normalize(message.url);
        if(!r.isOk() || UrlInput.hostOf(r.url) == null)
        {
            RejectLog.log(player, "computer cookies rejected (bad url) at " + message.pos);
            return;
        }
        CookieJar jar = ComputerCookies.get(computer);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for(String l : message.lines)
            changed |= jar.set(l, r.url, now);
        if(changed)
            ComputerCookies.changed(computer, jar);
    }
}
