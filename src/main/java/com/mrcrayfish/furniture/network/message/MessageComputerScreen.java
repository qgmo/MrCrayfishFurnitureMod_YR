package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ScreenThumb;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 → 客户端：电脑在世界里的屏幕显示的缩略图（{@link ScreenThumb#encodeFrame}；空的是清屏）。发给所有看得到这个区块的人
 * （悠然服务器魔改，服主 11-16）。
 */
public class MessageComputerScreen implements IMessage, IMessageHandler<MessageComputerScreen, IMessage>
{
    static final int MAX_LENGTH = 3 + ScreenThumb.MAX_COLORS * 3 + (ScreenThumb.MAX_WIDTH * ScreenThumb.MAX_HEIGHT + 1) / 2;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte[] frame = new byte[0];

    public MessageComputerScreen() {}

    public MessageComputerScreen(BlockPos pos, byte[] frame)
    {
        this.pos = pos;
        this.frame = frame;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        int len = buf.readUnsignedShort();
        if(len > MAX_LENGTH || len > buf.readableBytes())
            throw new IllegalArgumentException("computer screen frame too large");
        this.frame = new byte[len];
        buf.readBytes(this.frame);
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeShort(frame.length);
        buf.writeBytes(frame);
    }

    @Override
    public IMessage onMessage(MessageComputerScreen message, MessageContext ctx)
    {
        Minecraft.getMinecraft().addScheduledTask(() ->
        {
            Minecraft mc = Minecraft.getMinecraft();
            TileEntity te = mc.world == null || !mc.world.isBlockLoaded(message.pos) ? null : mc.world.getTileEntity(message.pos);
            if(te instanceof com.mrcrayfish.furniture.tileentity.TileEntityComputer)
                ((com.mrcrayfish.furniture.tileentity.TileEntityComputer) te).setScreenFrame(message.frame.length == 0 ? null : message.frame);
        });
        return null;
    }
}
