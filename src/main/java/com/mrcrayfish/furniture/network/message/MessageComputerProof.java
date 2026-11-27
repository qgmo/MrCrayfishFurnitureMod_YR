package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerProofs;
import com.mrcrayfish.furniture.network.ServerThread;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：浏览器提交带位置字段、操作者字段的表单前，请服务端给“我现在操作的这台电脑”签一份证明（悠然服务器魔改，服主 11-27）。
 * 包里只有电脑的位置、证明的种类、网站域名和随机数；地址取自电脑的网卡、坐标按电脑方块的位置、玩家就是发包的操作者，都由服务端自己定
 * （见 {@link ComputerProofs}）。结果用 {@link MessageComputerProofResult} 回。
 */
public class MessageComputerProof implements IMessage, IMessageHandler<MessageComputerProof, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private int requestId;
    private int kind;
    private String site = "";
    private String nonce = "";
    private boolean bad;

    public MessageComputerProof() {}

    public MessageComputerProof(BlockPos pos, int requestId, int kind, String site, String nonce)
    {
        this.pos = pos;
        this.requestId = requestId;
        this.kind = kind;
        this.site = site;
        this.nonce = nonce;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        try
        {
            this.pos = BlockPos.fromLong(buf.readLong());
            this.requestId = buf.readInt();
            this.kind = buf.readByte();
            this.site = ByteBufUtils.readUTF8String(buf);
            this.nonce = ByteBufUtils.readUTF8String(buf);
            this.bad = site.length() > 253 || nonce.length() > 128;
        }
        catch(RuntimeException e)
        {
            this.bad = true;
            buf.skipBytes(buf.readableBytes());
        }
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(requestId);
        buf.writeByte(kind);
        ByteBufUtils.writeUTF8String(buf, site);
        ByteBufUtils.writeUTF8String(buf, nonce);
    }

    @Override
    public IMessage onMessage(MessageComputerProof message, MessageContext ctx)
    {
        if(message.bad)
            return null;
        ServerThread.run(ctx, player -> ComputerProofs.request(player, message.pos, message.requestId, message.kind, message.site, message.nonce));
        return null;
    }
}
