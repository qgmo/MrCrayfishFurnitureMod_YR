package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.network.TvRequests;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 大屏右下角的“设置”按钮：请服务端打开频道设置界面（悠然服务器魔改）。界面有卡槽，要由服务端开。
 */
public class MessageTVOpenSettings implements IMessage, IMessageHandler<MessageTVOpenSettings, IMessage>
{
    /** 界面编号（GuiHandler） */
    public static final int GUI_ID = 12;

    private BlockPos pos;

    public MessageTVOpenSettings() {}

    public MessageTVOpenSettings(BlockPos pos)
    {
        this.pos = pos;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public IMessage onMessage(MessageTVOpenSettings message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageTVOpenSettings message, EntityPlayerMP player)
    {
        TileEntityTV tv = TvRequests.find(player, message.pos, TvRequests.SETTINGS_RANGE, TvRequests.SETTINGS, "TV settings");
        if(tv == null)
            return;
        if(tv.isDisabled())
        {
            RejectLog.log(player, "TV settings of disabled TV at " + message.pos);
            return;
        }
        player.openGui(MrCrayfishFurnitureMod.instance, GUI_ID, player.world, message.pos.getX(), message.pos.getY(), message.pos.getZ());
    }
}
