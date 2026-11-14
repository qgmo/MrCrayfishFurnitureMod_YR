package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.compat.RegionUse;
import com.mrcrayfish.furniture.computer.ComputerAccess;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 客户端 → 服务端：电脑的电源开关（操作者）、接管（没人操作时）（悠然服务器魔改） */
public class MessageComputerAction implements IMessage, IMessageHandler<MessageComputerAction, IMessage>
{
    public static final int POWER = 0, CLAIM = 1;

    private BlockPos pos = BlockPos.ORIGIN;
    private int action;

    public MessageComputerAction() {}

    public MessageComputerAction(BlockPos pos, int action)
    {
        this.pos = pos;
        this.action = action;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
    }

    @Override
    public IMessage onMessage(MessageComputerAction message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageComputerAction message, EntityPlayerMP player)
    {
        if(message.action == POWER)
        {
            TileEntityComputer computer = ComputerAccess.check(player, message.pos, true, false, "computer power switch");
            if(computer == null)
                return;
            if(!RegionUse.canUse(player.world, player, message.pos))
            {
                RejectLog.log(player, "computer power switch without permission at " + message.pos);
                return;
            }
            computer.togglePower();
        }
        else if(message.action == CLAIM)
        {
            TileEntityComputer computer = ComputerAccess.check(player, message.pos, false, false, "computer claim");
            if(computer != null && !computer.claim(player))
            {
                RejectLog.log(player, "computer claim refused at " + message.pos);
            }
        }
        else
        {
            RejectLog.log(player, "unknown computer action " + message.action);
        }
    }
}
