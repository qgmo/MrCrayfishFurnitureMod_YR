package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.blocks.BlockAbstractTV;
import com.mrcrayfish.furniture.client.DownloadUtils;
import com.mrcrayfish.furniture.client.GifCache;
import com.mrcrayfish.furniture.client.GifDownloadThread;
import com.mrcrayfish.furniture.handler.ConfigurationHandler;
import com.mrcrayfish.furniture.util.TileEntityUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.tv.RemoteAction;
import com.mrcrayfish.furniture.tv.TvChannel;
import com.mrcrayfish.furniture.tv.TvClock;
import com.mrcrayfish.furniture.tv.TvDurations;
import com.mrcrayfish.furniture.tv.TvPlayback;
import net.minecraft.util.SoundCategory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.ApplianceFaces;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.util.AppliancePower;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraft.util.ITickable;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：
 * <ul>
 * <li>要用电，开着放 GIF 时 2 FE/t、放视频时 4 FE/t；没电时黑屏，服务端的进度照走，来电接上。
 * 只从底面进电，能量单元卡插在频道设置界面里（{@link com.mrcrayfish.furniture.gui.containers.ContainerTVSettings}）。</li>
 * <li>频道可以是 GIF 网址或 bilibili 视频（{@link TvChannel}），三个频道可以混用。服务端只存频道、播放 / 暂停、
 * 基准位置与时刻（{@link TvPlayback}）、音量、开关机、弹幕开关；视频由每个客户端自己取、自己对齐，服务端不碰视频。</li>
 * <li>视频时长由在场玩家的客户端报上来（{@link #reportDuration}，{@link TvDurations}），采信时播到头停在片尾、暂停；
 * 换台、开机、改了当前频道时清掉重新收集。时长不存档（区块重新加载后由在场玩家再报）。</li>
 * <li>遥控器的操作见 {@link #handleRemote}；频道设置只从大屏的“设置”按钮进（潜行 + 空手右键留给 Carry On）。</li>
 * </ul>
 */
public class TileEntityTV extends TileEntitySyncClient implements ITickable, IAppliance
{
    /** 音量 0–100，遥控器每步 ±{@value #VOLUME_STEP} */
    public static final int VOLUME_STEP = 10;
    public static final int DEFAULT_VOLUME = 50;

    private final ApplianceEnergy energy = new ApplianceEnergy(this, AppliancePower.TV_VIDEO, ApplianceFaces.bottom(), this::sync);

    private int width;
    private int height;
    private double screenYOffset;
    private double screenZOffset;
    private boolean stretch;
    private boolean powered;
    private boolean disabled;

    private List<String> channels = new ArrayList<>();
    private int currentChannel;

    private TvPlayback playback = new TvPlayback();
    private boolean playbackStarted;
    /** 服务端：玩家报上来的视频时长。编号从随机数开始，区块重新加载后客户端会再报 */
    private final TvDurations<UUID> durations = new TvDurations<>(new Random().nextInt());
    /** 客户端：服务端的报告编号 */
    private int clientDurationEpoch;
    private int volume = DEFAULT_VOLUME;
    private boolean danmaku = true;

    /** 客户端：最近一次同步时服务端算好的位置、收到的本地时刻、进度条显示到什么时候、服务端是否关了视频 */
    private long clientPosMs;
    private long clientReceivedAt;
    private long clientOsdUntil;
    private boolean clientVideoOff;

    private String parsedRaw;
    private boolean parsedVideo;

    @SideOnly(Side.CLIENT)
    private boolean loading;
    @SideOnly(Side.CLIENT)
    private boolean loaded;
    @SideOnly(Side.CLIENT)
    private GifDownloadThread.ImageDownloadResult result;

    public TileEntityTV() {}

    public TileEntityTV(int width, int height, double screenYOffset, double screenZOffset)
    {
        this.width = width;
        this.height = height;
        this.screenYOffset = screenYOffset;
        this.screenZOffset = screenZOffset;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);
        NBTTagList channelList = new NBTTagList();
        channels.forEach(url -> channelList.appendTag(new NBTTagString(url)));
        compound.setTag("Channels", channelList);
        compound.setInteger("CurrentChannel", this.currentChannel);
        compound.setBoolean("Stretch", this.stretch);
        compound.setBoolean("Powered", this.powered);
        compound.setBoolean("DisableInteraction", this.disabled);
        compound.setBoolean("Playing", playback.isPlaying());
        compound.setLong("BasePos", playback.getBasePosMs());
        compound.setLong("BaseTime", playback.getBaseTimeMs());
        compound.setInteger("Volume", this.volume);
        compound.setBoolean("Danmaku", this.danmaku);
        this.energy.writeToNBT(compound);
        return compound;
    }

    /** 发给客户端的：存档内容 + 服务端此刻算好的位置、进度条剩余时间、视频功能是否关着 */
    @Override
    public NBTTagCompound getUpdateTag()
    {
        NBTTagCompound tag = super.getUpdateTag();
        long now = System.currentTimeMillis();
        tag.setLong("PosNow", playback.positionAt(now));
        tag.setLong("OsdLeft", playback.osdLeftMs(now));
        tag.setBoolean("VideoOff", !ConfigurationHandler.tvVideoEnabled);
        tag.setInteger("DurEpoch", durations.epoch());
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);
        this.channels.clear();
        if(compound.hasKey("Channels", Constants.NBT.TAG_LIST))
        {
            NBTTagList channelList = compound.getTagList("Channels", Constants.NBT.TAG_STRING);
            channelList.forEach(nbtBase ->
            {
                if(nbtBase instanceof NBTTagString)
                {
                    NBTTagString url = (NBTTagString) nbtBase;
                    channels.add(url.getString());
                }
            });
        }
        else if(compound.hasKey("URL", Constants.NBT.TAG_STRING))
        {
            this.channels.add(compound.getString("URL"));
        }
        if(compound.hasKey("CurrentChannel", Constants.NBT.TAG_INT))
        {
            this.currentChannel = compound.getInteger("CurrentChannel");
        }
        if(compound.hasKey("Stretch", Constants.NBT.TAG_BYTE))
        {
            this.stretch = compound.getBoolean("Stretch");
        }
        if(compound.hasKey("Powered", Constants.NBT.TAG_BYTE))
        {
            this.powered = compound.getBoolean("Powered");
        }
        if(compound.hasKey("DisableInteraction", Constants.NBT.TAG_BYTE))
        {
            this.disabled = compound.getBoolean("DisableInteraction");
        }
        if(compound.hasKey("BaseTime", Constants.NBT.TAG_LONG))
        {
            this.playback = new TvPlayback(compound.getBoolean("Playing"), compound.getLong("BasePos"), compound.getLong("BaseTime"));
            this.playbackStarted = true;
        }
        this.volume = compound.hasKey("Volume", Constants.NBT.TAG_INT) ? Math.max(0, Math.min(100, compound.getInteger("Volume"))) : DEFAULT_VOLUME;
        this.danmaku = !compound.hasKey("Danmaku", Constants.NBT.TAG_BYTE) || compound.getBoolean("Danmaku");
        this.energy.readFromNBT(compound);
        if(compound.hasKey("PosNow", Constants.NBT.TAG_LONG))
        {
            // 客户端收到同步：记下服务端的位置与收到的时刻（客户端的时钟和服务端对不上，不用 BaseTime）
            long now = System.currentTimeMillis();
            this.clientPosMs = compound.getLong("PosNow");
            this.clientReceivedAt = now;
            this.clientOsdUntil = now + Math.min(TvPlayback.OSD_MS, Math.max(0, compound.getLong("OsdLeft")));
            this.clientVideoOff = compound.getBoolean("VideoOff");
            this.clientDurationEpoch = compound.getInteger("DurEpoch");
        }
        if(world != null && world.isRemote && powered)
        {
            String channel = getCurrentChannel();
            if(channel != null && TvChannel.parse(channel).getKind() == TvChannel.Kind.GIF)
            {
                this.loadUrl(channel);
            }
        }
    }

    @Override
    public void update()
    {
        if(world == null || world.isRemote)
            return;
        if(!playbackStarted)
        {
            // 新放下的电视、改版前的存档：从现在开始算
            playbackStarted = true;
            restartPlayback(System.currentTimeMillis());
            markDirty();
        }
        // 采信了时长：播到头停在片尾、暂停
        if(playback.stopAtEnd(System.currentTimeMillis(), durations.trusted()))
        {
            markDirty();
            TileEntityUtil.syncToClient(this);
        }
        energy.tick(powered, isVideoChannel() ? AppliancePower.TV_VIDEO : AppliancePower.TV_GIF);
    }

    /** 当前频道是视频，并且服务端没关视频功能（用电按视频算） */
    public boolean isVideoChannel()
    {
        String channel = getCurrentChannel();
        if(channel == null || !ConfigurationHandler.tvVideoEnabled)
            return false;
        // 每 tick 都要问：解析结果按频道原文缓存
        if(!channel.equals(parsedRaw))
        {
            parsedRaw = channel;
            parsedVideo = TvChannel.parse(channel).isVideo();
        }
        return parsedVideo;
    }

    /** 屏幕亮着：开着并且这一 tick 付上了电 */
    public boolean isScreenOn()
    {
        return powered && energy.isRunning();
    }

    @Nullable
    public String getCurrentChannel()
    {
        if(channels.size() > 0 && currentChannel >= 0 && currentChannel < channels.size())
        {
            return channels.get(currentChannel);
        }
        return null;
    }

    @SideOnly(Side.CLIENT)
    public void loadUrl(String url)
    {
        if(loading)
            return;

        URI uri = DownloadUtils.createUri(url);
        if(uri == null)
        {
            this.result = GifDownloadThread.ImageDownloadResult.INVALID_URL;
            this.loaded = true;
            return;
        }

        if(!DownloadUtils.isValidScheme(uri))
        {
            this.result = GifDownloadThread.ImageDownloadResult.WRONG_SCHEME;
            this.loaded = true;
            return;
        }

        if(!DownloadUtils.isValidType(uri, "gif"))
        {
            this.result = GifDownloadThread.ImageDownloadResult.UNSUPPORTED_FILE_TYPE;
            this.loaded = true;
            return;
        }

        if(!DownloadUtils.isTrustedDomain(uri))
        {
            this.result = GifDownloadThread.ImageDownloadResult.UNTRUSTED;
            this.loaded = true;
            return;
        }

        this.loaded = false;
        this.result = null;
        if(!GifCache.INSTANCE.isCached(url))
        {
            this.loading = true;
            new GifDownloadThread(uri, (result, message) ->
            {
                this.loading = false;
                this.result = result;
                if(result == GifDownloadThread.ImageDownloadResult.SUCCESS)
                {
                    this.loaded = true;
                }
            }).start();
        }
        else
        {
            this.loaded = true;
        }
    }

    @SideOnly(Side.CLIENT)
    public boolean isLoading()
    {
        return loading;
    }

    @SideOnly(Side.CLIENT)
    public boolean isLoaded()
    {
        return loaded && !loading;
    }

    @Nullable
    @SideOnly(Side.CLIENT)
    public GifDownloadThread.ImageDownloadResult getResult()
    {
        return result;
    }

    public int getWidth()
    {
        return width;
    }

    public int getHeight()
    {
        return height;
    }

    public double getScreenYOffset()
    {
        return screenYOffset;
    }

    public double getScreenZOffset()
    {
        IBlockState state = world.getBlockState(pos);
        if(state.getBlock() instanceof BlockAbstractTV)
        {
            return ((BlockAbstractTV) state.getBlock()).getScreenZOffset(state);
        }
        return screenZOffset;
    }

    public List<String> getChannels()
    {
        return java.util.Collections.unmodifiableList(channels);
    }

    public int getCurrentChannelIndex()
    {
        return currentChannel;
    }

    /**
     * 频道设置界面保存（服务端，调用方已核对过每个频道的写法）。当前频道的内容变了就从头播。
     */
    public void applySettings(List<String> newChannels, boolean stretch)
    {
        String before = getCurrentChannel();
        channels.clear();
        for(String channel : newChannels)
        {
            String trimmed = channel == null ? "" : channel.trim();
            if(!trimmed.isEmpty())
            {
                channels.add(trimmed);
            }
        }
        if(currentChannel >= channels.size())
        {
            currentChannel = 0;
        }
        String after = getCurrentChannel();
        if(after == null ? before != null : !after.equals(before))
        {
            restartPlayback(System.currentTimeMillis());
        }
        this.stretch = stretch;
        this.markDirty();
        TileEntityUtil.syncToClient(this);
    }

    /**
     * 遥控器的一次操作（服务端，调用方已核对玩家、距离、电池并扣过电）。关机时只有开关机管用。
     *
     * @param clientDurationMs 按的人的客户端知道的视频时长（不知道为 0）：只在服务端没采信时长时用于后退，先把进度夹到片尾以内
     * @return 这次操作有没有改动（没有改动也已经扣了电）
     */
    public boolean handleRemote(RemoteAction action, long clientDurationMs)
    {
        if(disabled)
            return false;
        long now = System.currentTimeMillis();
        if(action == RemoteAction.POWER)
        {
            powered = !powered;
            if(powered)
            {
                // 关机再开机从头播（服主 11-14）
                restartPlayback(now);
            }
        }
        else if(!powered)
        {
            return false;
        }
        else
        {
            switch(action)
            {
                case TOGGLE_PLAY:
                {
                    // 播完停在片尾时按“播放”从头播（服主 11-16）；片尾按服务端采信的时长，没采信时按按的人的客户端知道的时长
                    long end = durations.trusted();
                    playback.playOrRestart(now, end > 0 ? end : (TvDurations.valid(clientDurationMs) ? clientDurationMs : 0));
                    playback.showOsd(now);
                    break;
                }
                case TOGGLE_OSD:
                    playback.toggleOsd(now);
                    break;
                case SEEK_BACK:
                {
                    // 采信了时长时按服务端的；没采信时按这个人的客户端知道的时长，把播完以后的进度先夹回片尾再往回退
                    long end = durations.trusted();
                    playback.rewind(now, TvPlayback.SEEK_STEP_MS, end > 0 ? end : (TvDurations.valid(clientDurationMs) ? clientDurationMs : 0));
                    playback.showOsd(now);
                    break;
                }
                case SEEK_FORWARD:
                    playback.seek(now, TvPlayback.SEEK_STEP_MS);
                    playback.showOsd(now);
                    break;
                case PREV_CHANNEL:
                case NEXT_CHANNEL:
                    if(channels.size() <= 1)
                        return false;
                    int step = action == RemoteAction.NEXT_CHANNEL ? 1 : channels.size() - 1;
                    currentChannel = (Math.max(0, currentChannel) + step) % channels.size();
                    restartPlayback(now);
                    world.playSound(null, pos, FurnitureSounds.white_noise, SoundCategory.BLOCKS, 0.5F, 1.0F);
                    break;
                case VOLUME_DOWN:
                    volume = Math.max(0, volume - VOLUME_STEP);
                    playback.showOsd(now);
                    break;
                case VOLUME_UP:
                    volume = Math.min(100, volume + VOLUME_STEP);
                    playback.showOsd(now);
                    break;
                case TOGGLE_DANMAKU:
                    danmaku = !danmaku;
                    playback.showOsd(now);
                    break;
                default:
                    return false;
            }
        }
        // 快进过了片尾：直接停在片尾
        playback.stopAtEnd(now, durations.trusted());
        markDirty();
        TileEntityUtil.syncToClient(this);
        return true;
    }

    /** 从头播，并清掉收集到的时长（换台、开机、改了当前频道） */
    private void restartPlayback(long now)
    {
        playback.restart(now);
        durations.clear();
    }

    /** 时长报告的结果 */
    public enum DurationReport
    {
        /** 收下了 */
        ACCEPTED,
        /** 换过台了（编号或频道对不上）、当前不是视频频道、人数满了：不收，也不算违规 */
        STALE,
        /** 时长不在合理范围内（大于 0、不超过 24 小时） */
        INVALID
    }

    /**
     * 在场玩家报上来的视频时长（服务端，调用方已核对玩家、距离、限频）。channelKey 是客户端在放的视频（{@link TvChannel#key()}）。
     */
    public DurationReport reportDuration(UUID player, int epoch, String channelKey, long durationMs)
    {
        if(!TvDurations.valid(durationMs))
            return DurationReport.INVALID;
        if(epoch != durations.epoch() || !isVideoChannel() || !TvChannel.parse(getCurrentChannel()).key().equals(channelKey))
            return DurationReport.STALE;
        long before = durations.trusted();
        boolean disagreedBefore = durations.disagree();
        if(!durations.report(player, durationMs))
            return DurationReport.STALE;
        long after = durations.trusted();
        if(after != before || durations.disagree() != disagreedBefore)
        {
            MrCrayfishFurnitureMod.logger().debug("TV at {}: {} duration report(s), {}", pos, durations.count(), after > 0 ? "using " + after + " ms" : "they disagree, not stopping at the end");
        }
        return DurationReport.ACCEPTED;
    }

    /** 客户端：服务端的时长报告编号 */
    public int getClientDurationEpoch()
    {
        return clientDurationEpoch;
    }

    public int getVolume()
    {
        return volume;
    }

    public boolean isDanmakuOn()
    {
        return danmaku;
    }

    /** 服务端此刻的位置（毫秒） */
    public long getServerPositionMs()
    {
        return playback.positionAt(System.currentTimeMillis());
    }

    public boolean isPlaying()
    {
        return playback.isPlaying();
    }

    /** 客户端：按最近一次同步算的时钟（latencyMs 是估计的单向延迟） */
    public TvClock clientClock(long latencyMs)
    {
        return new TvClock(clientPosMs, playback.isPlaying(), clientReceivedAt, latencyMs);
    }

    /** 客户端：进度条是否显示着 */
    public boolean isClientOsdShown()
    {
        return clientOsdUntil > System.currentTimeMillis();
    }

    /** 客户端：服务端关了视频功能（只放 GIF） */
    public boolean isClientVideoOff()
    {
        return clientVideoOff;
    }

    /** 拆电视时卡掉出来；容器界面判断玩家还能不能用 */
    public boolean isUsableByPlayer(EntityPlayer player)
    {
        return world != null && world.getTileEntity(pos) == this && player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    public boolean isStretched()
    {
        return stretch;
    }

    public boolean isPowered()
    {
        return powered;
    }

    public boolean isDisabled()
    {
        return disabled;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
            return energy.isInputFace(facing);
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
    {
        if(capability == CapabilityEnergy.ENERGY)
            return energy.isInputFace(facing) ? CapabilityEnergy.ENERGY.cast(energy.port()) : null;
        return super.getCapability(capability, facing);
    }

    @Override
    public ApplianceEnergy getAppliance()
    {
        return energy;
    }
}
