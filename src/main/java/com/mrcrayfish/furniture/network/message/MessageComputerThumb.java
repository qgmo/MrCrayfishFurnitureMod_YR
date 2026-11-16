package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.computer.ScreenThumb;
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
 * 客户端 → 服务端：开着电脑界面的人（操作者或旁观者）在本地画的屏幕缩略图（悠然服务器魔改，服主 11-16）。
 * 带上它对应的页面（上传编号）与滚动位置，服务端只算对得上当前页面的（见 {@link com.mrcrayfish.furniture.computer.ScreenVote}）。
 * 服务端查：开着这台电脑的界面、距离、区块已加载、电脑开着且有电，按玩家限频；宽高要等于配置、长度正好、每个像素都在调色板里。
 */
public class MessageComputerThumb implements IMessage, IMessageHandler<MessageComputerThumb, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private int page;
    private int scroll;
    private int width;
    private int height;
    private byte[] data = new byte[0];
    private boolean bad;

    public MessageComputerThumb() {}

    /** @param packed 打包好的像素（{@link ScreenThumb#pack}） */
    public MessageComputerThumb(BlockPos pos, int page, int scroll, int width, int height, byte[] packed)
    {
        this.pos = pos;
        this.page = page;
        this.scroll = scroll;
        this.width = width;
        this.height = height;
        this.data = packed;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.page = buf.readInt();
        this.scroll = buf.readInt();
        this.width = buf.readUnsignedByte();
        this.height = buf.readUnsignedByte();
        int len = buf.readUnsignedShort();
        if(len > (ScreenThumb.MAX_WIDTH * ScreenThumb.MAX_HEIGHT + 1) / 2 || len > buf.readableBytes())
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
        buf.writeInt(page);
        buf.writeInt(scroll);
        buf.writeByte(width);
        buf.writeByte(height);
        buf.writeShort(data.length);
        buf.writeBytes(data);
    }

    @Override
    public IMessage onMessage(MessageComputerThumb message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerThumb message, EntityPlayerMP player)
    {
        if(message.bad)
        {
            RejectLog.log(player, "computer thumbnail too large");
            return;
        }
        ScreenThumb.Spec spec = ComputerSync.screenSpec();
        if(!spec.enabled)
            return;
        if(!ComputerSync.allowThumb(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer thumbnail too fast");
            return;
        }
        // 操作者、旁观者都可以（开着这台电脑的界面就行），电脑要开着、有电
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, false, true, "computer thumbnail");
        if(computer == null)
            return;
        if(!computer.isSwitchedOn())
        {
            RejectLog.log(player, "computer thumbnail: computer is switched off at " + message.pos);
            return;
        }
        if(message.width != spec.width || message.height != spec.height)
        {
            RejectLog.log(player, "computer thumbnail size " + message.width + "x" + message.height + " does not match " + spec.width + "x" + spec.height + " at " + message.pos);
            return;
        }
        byte[] pixels = ScreenThumb.unpack(message.data, spec.pixels(), spec.colors());
        if(pixels == null)
        {
            RejectLog.log(player, "computer thumbnail data is malformed (" + message.data.length + " bytes) at " + message.pos);
            return;
        }
        // 过时的（页面、滚动位置对不上）不算，是正常的先后，不记日志
        computer.submitThumb(player, message.page, message.scroll, pixels);
    }
}
