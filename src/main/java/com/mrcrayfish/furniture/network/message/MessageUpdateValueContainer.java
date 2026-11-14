package com.mrcrayfish.furniture.network.message;

import com.google.common.collect.Maps;
import com.mrcrayfish.furniture.gui.components.ValueComponent;
import com.mrcrayfish.furniture.tileentity.IValueContainer;
import com.mrcrayfish.furniture.tileentity.TileEntityPhotoFrame;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.util.RejectLog;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import com.mrcrayfish.furniture.network.ServerThread;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Author: MrCrayfish
 */
public class MessageUpdateValueContainer implements IMessage, IMessageHandler<MessageUpdateValueContainer, IMessage>
{
    private Map<String, String> entryMap;
    private BlockPos pos;

    public MessageUpdateValueContainer() {}

    public MessageUpdateValueContainer(List<ValueComponent> valueEntries, IValueContainer valueContainer)
    {
        this.pos = valueContainer.getContainerPos();
        this.entryMap = valueEntries.stream().collect(Collectors.toMap(ValueComponent::getId, ValueComponent::getValue));
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(entryMap.size());
        entryMap.forEach((key, value) ->
        {
            ByteBufUtils.writeUTF8String(buf, key);
            ByteBufUtils.writeUTF8String(buf, value);
        });
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        pos = BlockPos.fromLong(buf.readLong());
        int size = buf.readInt();
        entryMap = Maps.newHashMap();
        for(int i = 0; i < size; i++)
        {
            String id = ByteBufUtils.readUTF8String(buf);
            String value = ByteBufUtils.readUTF8String(buf);
            entryMap.put(id, value);
        }
    }

    @Override
    public IMessage onMessage(MessageUpdateValueContainer message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageUpdateValueContainer message, EntityPlayerMP player)
    {
        //if(player.isCreative()) //TODO make TV not use value container implementation
        {
            World world = player.getServerWorld();
            if(!world.isAreaLoaded(message.pos, 0))
                return;

            double distance = player.getPosition().getDistance(message.pos.getX(), message.pos.getY(), message.pos.getZ());
            if(distance > 8)
                return;

            TileEntity tileEntity = world.getTileEntity(message.pos);
            // 被设为“禁止交互”的相框、电视，界面打不开，编辑包也一律拒绝
            if((tileEntity instanceof TileEntityPhotoFrame && ((TileEntityPhotoFrame) tileEntity).isDisabled())
                    || (tileEntity instanceof TileEntityTV && ((TileEntityTV) tileEntity).isDisabled()))
            {
                RejectLog.log(player, "edit of disabled " + tileEntity.getClass().getSimpleName() + " at " + message.pos);
                return;
            }
            if(tileEntity instanceof IValueContainer)
            {
                String result = ((IValueContainer) tileEntity).updateEntries(message.entryMap, player);
                if(result != null)
                {
                    RejectLog.log(player, result);
                    return;
                }
                TileEntityUtil.syncToClient(tileEntity);
            }
        }
    }
}
