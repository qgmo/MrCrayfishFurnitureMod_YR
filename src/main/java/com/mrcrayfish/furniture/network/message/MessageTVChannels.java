package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.gui.containers.ContainerTVSettings;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.network.TvRequests;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.TvChannel;
import com.mrcrayfish.furniture.util.RejectLog;
import com.mrcrayfish.furniture.util.TextLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Arrays;
import java.util.List;

/**
 * 频道设置界面的“保存”（客户端 → 服务端，悠然服务器魔改）。服务端只收开着这台电视设置界面的玩家发的，
 * 逐个核对频道的长度与写法，结果用 {@link MessageTVChannelsResult} 回给客户端（界面只在成功后才清掉草稿）。
 */
public class MessageTVChannels implements IMessage, IMessageHandler<MessageTVChannels, IMessage>
{
    public static final int CHANNELS = 3;

    private BlockPos pos;
    private int requestId;
    private String[] channels = new String[CHANNELS];
    private boolean stretch;
    /** 包里的字符串太长（解包时就发现） */
    private boolean malformed;

    public MessageTVChannels() {}

    public MessageTVChannels(BlockPos pos, int requestId, String[] channels, boolean stretch)
    {
        this.pos = pos;
        this.requestId = requestId;
        this.channels = channels.clone();
        this.stretch = stretch;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(requestId);
        for(int i = 0; i < CHANNELS; i++)
        {
            ByteBufUtils.writeUTF8String(buf, channels[i] == null ? "" : channels[i]);
        }
        buf.writeBoolean(stretch);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        pos = BlockPos.fromLong(buf.readLong());
        requestId = buf.readInt();
        for(int i = 0; i < CHANNELS; i++)
        {
            String s = ByteBufUtils.readUTF8String(buf);
            if(s.length() > TextLimits.MAX_URL_LENGTH)
            {
                malformed = true;
                s = "";
            }
            channels[i] = s;
        }
        stretch = buf.readBoolean();
    }

    @Override
    public IMessage onMessage(MessageTVChannels message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageTVChannels message, EntityPlayerMP player)
    {
        if(message.malformed)
        {
            RejectLog.log(player, "TV channel longer than " + TextLimits.MAX_URL_LENGTH);
            reply(player, message, "cfm.tv.channel.too_long", -1);
            return;
        }
        if(!(player.openContainer instanceof ContainerTVSettings) || !((ContainerTVSettings) player.openContainer).getTv().getPos().equals(message.pos))
        {
            RejectLog.log(player, "TV channels for " + message.pos + " without its settings screen open");
            reply(player, message, "cfm.tv.settings.closed", -1);
            return;
        }
        TileEntityTV tv = TvRequests.find(player, message.pos, TvRequests.SETTINGS_RANGE, TvRequests.SETTINGS, "TV channels");
        if(tv == null || tv != ((ContainerTVSettings) player.openContainer).getTv())
        {
            reply(player, message, "cfm.tv.settings.rejected", -1);
            return;
        }
        if(tv.isDisabled())
        {
            RejectLog.log(player, "TV channels of disabled TV at " + message.pos);
            reply(player, message, "cfm.tv.settings.rejected", -1);
            return;
        }
        for(int i = 0; i < CHANNELS; i++)
        {
            String reason = TvChannel.rejectReason(message.channels[i]);
            if(reason != null)
            {
                // 写法不对是玩家常见的手误，回给界面提示即可，不记日志
                reply(player, message, reason, i);
                return;
            }
        }
        List<String> list = Arrays.asList(message.channels);
        tv.applySettings(list, message.stretch);
        reply(player, message, null, -1);
    }

    private static void reply(EntityPlayerMP player, MessageTVChannels message, String reason, int index)
    {
        PacketHandler.INSTANCE.sendTo(new MessageTVChannelsResult(message.pos, message.requestId, reason, index), player);
    }
}
