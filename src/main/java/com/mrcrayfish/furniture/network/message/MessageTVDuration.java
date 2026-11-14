package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.network.ServerThread;
import com.mrcrayfish.furniture.network.TvRequests;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.TvDurations;
import com.mrcrayfish.furniture.util.RejectLog;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端取到视频后报时长（客户端 → 服务端，悠然服务器魔改，服主 11-14）。每台电视每个报告编号（换台、开机后服务端会换）只报一次。
 * 服务端核对：限频（{@link TvRequests#DURATION}）、区块已加载、是电视、玩家在 {@value TvRequests#DURATION_RANGE} 格内、
 * 时长在合理范围内（大于 0、不超过 24 小时）；编号或频道对不上（刚换过台）的不收也不记日志。是否采信见 {@link TvDurations}。
 */
public class MessageTVDuration implements IMessage, IMessageHandler<MessageTVDuration, IMessage>
{
    /** 频道键（{@link com.mrcrayfish.furniture.tv.TvChannel#key()}）最长多少字：BV 号 + 分 P，远用不到 */
    private static final int MAX_KEY = 64;

    private BlockPos pos;
    private int epoch;
    private String channelKey;
    private long durationMs;

    public MessageTVDuration() {}

    public MessageTVDuration(BlockPos pos, int epoch, String channelKey, long durationMs)
    {
        this.pos = pos;
        this.epoch = epoch;
        this.channelKey = channelKey;
        this.durationMs = durationMs;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        buf.writeInt(epoch);
        ByteBufUtils.writeUTF8String(buf, channelKey.length() > MAX_KEY ? channelKey.substring(0, MAX_KEY) : channelKey);
        buf.writeLong(durationMs);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        pos = BlockPos.fromLong(buf.readLong());
        epoch = buf.readInt();
        channelKey = ByteBufUtils.readUTF8String(buf);
        durationMs = buf.readLong();
    }

    @Override
    public IMessage onMessage(MessageTVDuration message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageTVDuration message, EntityPlayerMP player)
    {
        if(message.channelKey.length() > MAX_KEY)
        {
            RejectLog.log(player, "TV duration report with a channel key of " + message.channelKey.length() + " characters");
            return;
        }
        TileEntityTV tv = TvRequests.find(player, message.pos, TvRequests.DURATION_RANGE, TvRequests.DURATION, "TV duration report", false);
        if(tv == null)
            return;
        if(tv.reportDuration(player.getUniqueID(), message.epoch, message.channelKey, message.durationMs) == TileEntityTV.DurationReport.INVALID)
        {
            RejectLog.log(player, "TV duration report of " + message.durationMs + " ms at " + message.pos);
        }
    }
}
