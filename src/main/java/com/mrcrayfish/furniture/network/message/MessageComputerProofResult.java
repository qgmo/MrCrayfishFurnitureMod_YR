package com.mrcrayfish.furniture.network.message;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 服务端 → 客户端：{@link MessageComputerProof} 的结果：证明全文，或拿不到时 {@code error:代号}（悠然服务器魔改，服主 11-27） */
public class MessageComputerProofResult implements IMessage, IMessageHandler<MessageComputerProofResult, IMessage>
{
    /** 证明最长（位置证明、操作者证明都不到 1024 字节） */
    public static final int MAX = 1024;

    private int requestId;
    private String value = "";

    public MessageComputerProofResult() {}

    public MessageComputerProofResult(int requestId, String value)
    {
        this.requestId = requestId;
        this.value = value;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        try
        {
            this.requestId = buf.readInt();
            this.value = ByteBufUtils.readUTF8String(buf);
            if(value.length() > MAX)
                value = "error:unavailable";
        }
        catch(RuntimeException e)
        {
            this.value = "error:unavailable";
            buf.skipBytes(buf.readableBytes());
        }
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(requestId);
        ByteBufUtils.writeUTF8String(buf, value);
    }

    @Override
    public IMessage onMessage(MessageComputerProofResult message, MessageContext ctx)
    {
        com.mrcrayfish.furniture.client.browser.ClientProofs.onResult(message.requestId, message.value);
        return null;
    }
}
