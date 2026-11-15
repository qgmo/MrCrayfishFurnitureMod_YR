package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.HomePage;
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

/**
 * 客户端 → 服务端：把这台电脑的主页设成某个网址（空串为恢复服务器默认）（悠然服务器魔改，服主 11-15）。
 * 服务端查：是当前操作者、距离（不要求有电），按玩家限频；网址要是规范化过的 http / https 或 about:home。
 */
public class MessageComputerHome implements IMessage, IMessageHandler<MessageComputerHome, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private String url = "";
    private boolean bad;

    public MessageComputerHome() {}

    public MessageComputerHome(BlockPos pos, String url)
    {
        this.pos = pos;
        this.url = url;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        int len = buf.readUnsignedShort();
        if(len > UrlInput.MAX_LENGTH * 3 || len > buf.readableBytes())
        {
            this.bad = true;
            buf.skipBytes(buf.readableBytes());
            return;
        }
        byte[] b = new byte[len];
        buf.readBytes(b);
        this.url = new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        byte[] b = url.getBytes(StandardCharsets.UTF_8);
        buf.writeLong(pos.toLong());
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    @Override
    public IMessage onMessage(MessageComputerHome message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerHome message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer home page too long");
            return;
        }
        if(!ComputerSync.allowAction(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer actions too fast at " + message.pos);
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, false, "computer home page");
        if(computer == null)
            return;
        if(!message.url.isEmpty() && !HomePage.isValid(message.url))
        {
            RejectLog.log(player, "computer home page rejected (not a normalized http/https address) at " + message.pos);
            return;
        }
        computer.setHomePage(message.url.isEmpty() ? null : message.url);
        ComputerSync.sendHome(player, computer);
    }
}
