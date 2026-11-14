package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.entity.EntitySeat;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.network.ServerThread;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

//Server Packet
public class MessageFart implements IMessage, IMessageHandler<MessageFart, IMessage>
{
    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    @Override
    public IMessage onMessage(MessageFart message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageFart message, EntityPlayerMP player)
    {
        if(player.getRidingEntity() instanceof EntitySeat)
        {
            player.world.playSound(null, player.getPosition(), FurnitureSounds.getRandomFart(player.getRNG()), SoundCategory.BLOCKS, 0.75F, player.getRNG().nextFloat());
            Triggers.trigger(Triggers.PLAYER_FART, player);
        }
    }
}
