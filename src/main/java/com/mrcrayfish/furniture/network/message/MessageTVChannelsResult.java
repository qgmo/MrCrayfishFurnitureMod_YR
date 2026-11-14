package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;

/**
 * 保存频道的结果（服务端 → 客户端，悠然服务器魔改）：成功时 reason 为空；失败时是语言键，index 是第几个频道有问题（-1 表示不是某一个）。
 */
public class MessageTVChannelsResult implements IMessage, IMessageHandler<MessageTVChannelsResult, IMessage>
{
    private BlockPos pos;
    private int requestId;
    private String reason;
    private int index;

    public MessageTVChannelsResult() {}

    public MessageTVChannelsResult(BlockPos pos, int requestId, @Nullable String reason, int index)
    {
        this.pos = pos;
        this.requestId = requestId;
        this.reason = reason == null ? "" : reason;
        this.index = index;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(requestId);
        ByteBufUtils.writeUTF8String(buf, reason);
        buf.writeByte(index);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        pos = BlockPos.fromLong(buf.readLong());
        requestId = buf.readInt();
        reason = ByteBufUtils.readUTF8String(buf);
        index = buf.readByte();
    }

    public BlockPos getPos()
    {
        return pos;
    }

    public int getRequestId()
    {
        return requestId;
    }

    /** 成功时为 null */
    @Nullable
    public String getReason()
    {
        return reason.isEmpty() ? null : reason;
    }

    public int getIndex()
    {
        return index;
    }

    @Override
    public IMessage onMessage(MessageTVChannelsResult message, MessageContext ctx)
    {
        MrCrayfishFurnitureMod.proxy.onTvChannelsResult(message);
        return null;
    }
}
