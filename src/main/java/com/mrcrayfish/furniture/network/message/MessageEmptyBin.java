package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.gui.containers.ContainerBin;
import com.mrcrayfish.furniture.tileentity.TileEntityBin;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import com.mrcrayfish.furniture.network.ServerThread;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

//Server Packet
public class MessageEmptyBin implements IMessage, IMessageHandler<MessageEmptyBin, IMessage>
{
    private int x, y, z;

    public MessageEmptyBin() {}

    public MessageEmptyBin(int x, int y, int z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    @Override
    public IMessage onMessage(MessageEmptyBin message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageEmptyBin message, EntityPlayerMP player)
    {
        World world = player.world;
        BlockPos pos = new BlockPos(message.x, message.y, message.z);
        if(!world.isAreaLoaded(pos, 0))
            return;

        Container container = player.openContainer;
        if(!(container instanceof ContainerBin))
            return;

        TileEntity tile_entity = world.getTileEntity(pos);
        if(tile_entity instanceof TileEntityBin)
        {
            TileEntityBin tileEntityBin = (TileEntityBin) tile_entity;
            if(((ContainerBin) container).getBinInventory() == tileEntityBin)
            {
                tileEntityBin.clear();
                TileEntityUtil.markBlockForUpdate(player.world, pos);
            }
        }
    }
}
