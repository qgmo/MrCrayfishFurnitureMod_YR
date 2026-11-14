package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.gui.containers.ContainerFreezer;
import com.mrcrayfish.furniture.tileentity.TileEntityDoorMat;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.util.RejectLog;
import com.mrcrayfish.furniture.util.TextLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageDoorMat implements IMessage, IMessageHandler<MessageDoorMat, IMessage>
{
    public int x, y, z;
    private String message;

    public MessageDoorMat() {}

    public MessageDoorMat(int x, int y, int z, String message)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.message = message;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        ByteBufUtils.writeUTF8String(buf, message);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public IMessage onMessage(MessageDoorMat message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageDoorMat message, EntityPlayerMP player)
    {
        World world = player.world;
        BlockPos pos = new BlockPos(message.x, message.y, message.z);
        if(!world.isBlockLoaded(pos))
            return;
        // 距离 ≤ 8，且只有刚放下这块门垫的人能写一次（文字界面只在放下时打开）
        if(player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 64.0D)
        {
            RejectLog.log(player, "door mat edit too far at " + pos);
            return;
        }
        TileEntity tileEntity = world.getTileEntity(pos);
        if(tileEntity instanceof TileEntityDoorMat)
        {
            TileEntityDoorMat doorMat = (TileEntityDoorMat) tileEntity;
            if(!doorMat.isEditor(player.getUniqueID()))
            {
                RejectLog.log(player, "door mat edit without placing it at " + pos);
                return;
            }
            doorMat.setEditor(null);
            doorMat.setMessage(TextLimits.truncate(message.message, TextLimits.MAX_DOOR_MAT_LENGTH));
            TileEntityUtil.markBlockForUpdate(world, pos);
        }
    }
}
