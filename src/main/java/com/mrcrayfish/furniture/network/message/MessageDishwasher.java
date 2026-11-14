package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.tileentity.TileEntityDishwasher;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.gui.containers.ContainerDishwasher;
import io.netty.buffer.ByteBuf;
import net.minecraft.inventory.Container;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageDishwasher implements IMessage, IMessageHandler<MessageDishwasher, IMessage>
{
    private int type;
    private int x, y, z;

    public MessageDishwasher() {}

    public MessageDishwasher(int type, int x, int y, int z)
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
    public IMessage onMessage(MessageDishwasher message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageDishwasher message, EntityPlayerMP player)
    {
        World world = player.world;
        BlockPos pos = new BlockPos(message.x, message.y, message.z);
        if(!world.isAreaLoaded(pos, 0))
            return;

        // 与其它机器一致：发包人必须开着这台洗碗机的界面
        Container container = player.openContainer;
        if(!(container instanceof ContainerDishwasher))
            return;

        TileEntity tileEntity = world.getTileEntity(pos);
        if(tileEntity instanceof TileEntityDishwasher)
        {
            TileEntityDishwasher tileEntityDishwasher = (TileEntityDishwasher) tileEntity;
            if(((ContainerDishwasher) container).getDishwasherInventory() != tileEntityDishwasher)
                return;
            if(message.type == 0)
            {
                tileEntityDishwasher.startWashing();
            }
            if(message.type == 1)
            {
                tileEntityDishwasher.stopWashing();
            }
            TileEntityUtil.markBlockForUpdate(world, pos);
        }
    }
}
