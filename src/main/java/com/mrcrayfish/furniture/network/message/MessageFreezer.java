package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.gui.containers.ContainerBin;
import com.mrcrayfish.furniture.gui.containers.ContainerFreezer;
import com.mrcrayfish.furniture.tileentity.TileEntityBin;
import com.mrcrayfish.furniture.tileentity.TileEntityFreezer;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import com.mrcrayfish.furniture.network.ServerThread;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageFreezer implements IMessage, IMessageHandler<MessageFreezer, IMessage>
{
    private int type;
    private int x, y, z;

    public MessageFreezer() {}

    public MessageFreezer(int type, int x, int y, int z)
    {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.type = buf.readInt();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(type);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    @Override
    public IMessage onMessage(MessageFreezer message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageFreezer message, EntityPlayerMP player)
    {
        World world = player.world;
        BlockPos pos = new BlockPos(message.x, message.y, message.z);
        if(!world.isAreaLoaded(pos, 0))
            return;

        Container container = player.openContainer;
        if(!(container instanceof ContainerFreezer))
            return;

        TileEntity tileEntity = world.getTileEntity(pos);
        if(tileEntity instanceof TileEntityFreezer)
        {
            TileEntityFreezer tileEntityFreezer = (TileEntityFreezer) tileEntity;
            if(((ContainerFreezer) container).getFreezerInventory() != tileEntityFreezer)
                return;

            if(message.type == 0)
            {
                tileEntityFreezer.startFreezing();
            }
            if(message.type == 1)
            {
                tileEntityFreezer.stopFreezing();
            }
            TileEntityUtil.markBlockForUpdate(player.world, pos);
        }
    }
}
