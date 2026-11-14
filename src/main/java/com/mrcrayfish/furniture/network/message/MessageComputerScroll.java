package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.computer.ComputerSync;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 客户端 → 服务端：操作者把页面滚到了哪里（给旁观者跟着滚，按玩家限频）（悠然服务器魔改） */
public class MessageComputerScroll implements IMessage, IMessageHandler<MessageComputerScroll, IMessage>
{
    /** 页面最高 4096 像素（见浏览器的渲染上限），再多没意义 */
    static final int MAX_SCROLL = 1 << 16;

    private BlockPos pos = BlockPos.ORIGIN;
    private int scroll;

    public MessageComputerScroll() {}

    public MessageComputerScroll(BlockPos pos, int scroll)
    {
        this.pos = pos;
        this.scroll = scroll;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.scroll = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(scroll);
    }

    @Override
    public IMessage onMessage(MessageComputerScroll message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerScroll message, EntityPlayerMP player)
    {
        if(message.scroll < 0 || message.scroll > MAX_SCROLL)
        {
            RejectLog.log(player, "computer scroll out of range " + message.scroll);
            return;
        }
        if(!ComputerSync.allowScroll(player, System.currentTimeMillis()))
        {
            RejectLog.log(player, "computer scroll too fast");
            return;
        }
        TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, true, "computer scroll");
        if(computer != null)
            computer.setScroll(message.scroll);
    }
}
