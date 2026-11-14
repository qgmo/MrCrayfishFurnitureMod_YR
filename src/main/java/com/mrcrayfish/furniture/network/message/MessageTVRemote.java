package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.items.ItemTVRemote;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.network.TvRequests;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.RemoteAction;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 遥控器的一次操作（客户端 → 服务端，悠然服务器魔改）。按法由客户端判断（{@link com.mrcrayfish.furniture.tv.RemoteInput}），
 * 服务端核对电视、距离、使用权、手里是遥控器且有电（每次扣 2 FE），再改电视的状态并广播。
 */
public class MessageTVRemote implements IMessage, IMessageHandler<MessageTVRemote, IMessage>
{
    private BlockPos pos;
    private int action;

    public MessageTVRemote() {}

    public MessageTVRemote(BlockPos pos, RemoteAction action)
    {
        this.pos = pos;
        this.action = action.ordinal();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        pos = BlockPos.fromLong(buf.readLong());
        action = buf.readUnsignedByte();
    }

    @Override
    public IMessage onMessage(MessageTVRemote message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageTVRemote message, EntityPlayerMP player)
    {
        RemoteAction action = RemoteAction.byId(message.action);
        if(action == null)
        {
            RejectLog.log(player, "unknown TV remote action " + message.action);
            return;
        }
        // 主手优先；看大屏时也可以拿在副手
        ItemStack remote = player.getHeldItemMainhand();
        if(!(remote.getItem() instanceof ItemTVRemote))
            remote = player.getHeldItemOffhand();
        if(!(remote.getItem() instanceof ItemTVRemote))
        {
            RejectLog.log(player, "TV remote action without a remote in hand");
            return;
        }
        TileEntityTV tv = TvRequests.find(player, message.pos, TvRequests.REMOTE_RANGE, TvRequests.REMOTE, "TV remote " + action);
        if(tv == null)
            return;
        if(tv.isDisabled())
        {
            RejectLog.log(player, "TV remote on disabled TV at " + message.pos);
            return;
        }
        if(!ItemTVRemote.pressButton(remote))
        {
            ItemTVRemote.notifyNoCharge(player, remote);
            return;
        }
        tv.handleRemote(action);
    }
}
