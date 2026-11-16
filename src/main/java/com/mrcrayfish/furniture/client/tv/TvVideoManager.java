package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageTVDuration;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.TvChannel;
import com.mrcrayfish.furniture.tv.TvClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 客户端：哪些电视放视频（悠然服务器魔改，服主 11-14 定的数字都可在本地配置里改）。
 * <ul>
 * <li>开着、有电、当前频道是 bilibili 视频、在 {@code start-range}（默认 32 格）内的电视开始取流、显示；</li>
 * <li>按距离（大屏看的那台最优先）取前 {@code max-decoders}（默认 2）台解码，其余显示封面；</li>
 * <li>{@code hear-range}（默认 16 格）内有声音，按距离线性衰减；音量 = 电视音量 × 游戏的主音量与“唱片机/音符盒”音量 × 电视总音量。</li>
 * </ul>
 * 没电（黑屏）超过 2 秒才停掉解码，来电后按服务端的位置接上。
 * <p>
 * 取到视频时长后报给服务端（{@link MessageTVDuration}），每台电视每个报告编号（服务端换台、开机后会换）只报一次。
 */
public final class TvVideoManager
{
    public static final TvVideoManager INSTANCE = new TvVideoManager();

    private static final long OFF_GRACE_MS = 2_000L;

    /** 渲染器要画什么 */
    public static final class Screen
    {
        public enum Kind
        {
            NOISE, VIDEO, COVER, TEXT
        }

        public final Kind kind;
        public final int texture;
        public final int width;
        public final int height;
        public final String text;
        @Nullable
        public final VideoSession session;

        private Screen(Kind kind, int texture, int width, int height, String text, @Nullable VideoSession session)
        {
            this.kind = kind;
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.text = text;
            this.session = session;
        }

        static Screen noise(@Nullable VideoSession session)
        {
            return new Screen(Kind.NOISE, -1, 0, 0, "", session);
        }

        static Screen text(String text, @Nullable VideoSession session)
        {
            return new Screen(Kind.TEXT, -1, 0, 0, text, session);
        }
    }

    private final Map<BlockPos, VideoSession> sessions = new HashMap<>();
    private final Map<BlockPos, Long> offSince = new HashMap<>();
    /** 已经报过时长的：电视位置 → 报告编号与频道 */
    private final Map<BlockPos, String> reported = new HashMap<>();
    @Nullable
    private BlockPos fullscreen;
    /** 换清晰度上限重开会话时，旧会话留着画到新会话出第一帧（避免黑一下），声音静音 */
    private final Map<BlockPos, VideoSession> retiring = new HashMap<>();
    private int ticks;
    private long lastSyncLog;

    private TvVideoManager() {}

    public void setFullscreen(@Nullable BlockPos pos)
    {
        this.fullscreen = pos;
        select();
    }

    /**
     * 一台电视的清晰度上限：玩家设置的生效上限只在这台是大屏时用，否则最高 480P。
     */
    static int capFor(int effectiveQuality, boolean fullscreen)
    {
        return fullscreen ? effectiveQuality : Math.min(effectiveQuality, BiliData.QN_480P);
    }

    /** 现有会话的上限和现在该用的不一样才重开（玩家设置本来就 ≤480P 或没 cookie 时，进出大屏都不会重开） */
    static boolean needsRestart(int currentCap, int effectiveQuality, boolean fullscreen)
    {
        return currentCap != capFor(effectiveQuality, fullscreen);
    }

    @Nullable
    public BlockPos getFullscreen()
    {
        return fullscreen;
    }

    @Nullable
    public VideoSession session(BlockPos pos)
    {
        return sessions.get(pos);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if(event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.world == null || mc.player == null)
        {
            closeAll();
            return;
        }
        if(++ticks % 5 == 0)
            select();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event)
    {
        if(event.getWorld().isRemote)
        {
            closeAll();
            CoverCache.clear();
        }
    }

    /** 每帧：更新各台的画面与声音 */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if(event.phase != TickEvent.Phase.START || sessions.isEmpty())
            return;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        EntityPlayer player = mc.player;
        if(world == null || player == null)
            return;
        long now = System.currentTimeMillis();
        long latency = latencyMs(mc);
        TvClientConfig.Values cfg = TvClientConfig.get();
        float base = mc.gameSettings.getSoundLevel(SoundCategory.MASTER) * mc.gameSettings.getSoundLevel(SoundCategory.RECORDS) * cfg.masterVolume / 100F;
        boolean logSync = Boolean.getBoolean("cfm.tv.logSync") && now - lastSyncLog > 1000;
        if(logSync)
            lastSyncLog = now;
        for(VideoSession session : sessions.values())
        {
            TileEntity te = world.getTileEntity(session.getPos());
            if(!(te instanceof TileEntityTV))
                continue;
            TileEntityTV tv = (TileEntityTV) te;
            TvClock clock = tv.clientClock(latency);
            BlockPos p = session.getPos();
            double x = p.getX() + 0.5, y = p.getY() + 0.5, z = p.getZ() + 0.5;
            boolean full = p.equals(fullscreen);
            float gain = base * tv.getVolume() / 100F;
            if(!full)
            {
                double d = Math.sqrt(player.getDistanceSq(x, y, z));
                gain *= (float) Math.max(0, 1 - d / Math.max(1, cfg.hearRange));
            }
            if(!tv.isScreenOn())
                gain = 0;
            try
            {
                session.update(clock, now, gain, x, y, z, full);
            }
            catch(RuntimeException e)
            {
                MrCrayfishFurnitureMod.logger().warn("TV video at {}: {}", p, e.toString());
            }
            VideoSession old = retiring.get(p);
            if(old != null)
            {
                try
                {
                    old.update(clock, now, 0, x, y, z, false);
                }
                catch(RuntimeException e)
                {
                    MrCrayfishFurnitureMod.logger().warn("TV video at {}: {}", p, e.toString());
                }
            }
            if(logSync)
            {
                // 测多人同步用：本地位置与目标位置（-Dcfm.tv.logSync=true 时）
                MrCrayfishFurnitureMod.logger().info("TV sync {} now={} local={} target={} playing={} status={}", p, now, session.localPositionMs(), clock.targetAt(now), clock.isPlaying(), session.getStatus());
            }
        }
    }

    static long latencyMs(Minecraft mc)
    {
        if(mc.getConnection() == null || mc.player == null)
            return 0;
        NetworkPlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUniqueID());
        return info == null ? 0 : TvClock.latencyFromPing(info.getResponseTime());
    }

    /** 选出要解码的电视 */
    private void select()
    {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        EntityPlayer player = mc.player;
        if(world == null || player == null)
            return;
        TvClientConfig.Values cfg = TvClientConfig.get();
        long now = System.currentTimeMillis();
        List<TileEntityTV> candidates = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for(TileEntity t : world.loadedTileEntityList)
        {
            if(!(t instanceof TileEntityTV) || t.isInvalid())
                continue;
            TileEntityTV tv = (TileEntityTV) t;
            if(!wantsVideo(tv))
                continue;
            BlockPos p = tv.getPos();
            double d = Math.sqrt(player.getDistanceSq(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            boolean full = p.equals(fullscreen);
            if(d > cfg.startRange && !full)
                continue;
            if(!tv.isScreenOn())
            {
                Long since = offSince.get(p);
                if(since == null)
                    offSince.put(p, now);
                if(since == null || now - since < OFF_GRACE_MS)
                {
                    // 刚断电：先留着
                    if(sessions.containsKey(p))
                    {
                        candidates.add(tv);
                        distances.add(full ? -1.0 : d);
                    }
                }
                continue;
            }
            offSince.remove(p);
            candidates.add(tv);
            distances.add(full ? -1.0 : d);
        }
        // 按距离排（大屏的那台距离记为 -1）
        List<Integer> order = new ArrayList<>();
        for(int i = 0; i < candidates.size(); i++)
            order.add(i);
        order.sort((a, b) -> Double.compare(distances.get(a), distances.get(b)));
        Map<BlockPos, TvChannel> chosen = new HashMap<>();
        if(!candidates.isEmpty() && NativeLibs.ensure())
        {
            for(int i = 0; i < order.size() && chosen.size() < cfg.maxDecoders; i++)
            {
                TileEntityTV tv = candidates.get(order.get(i));
                chosen.put(tv.getPos(), TvChannel.parse(tv.getCurrentChannel()));
            }
        }
        Iterator<Map.Entry<BlockPos, VideoSession>> it = sessions.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<BlockPos, VideoSession> e = it.next();
            TvChannel want = chosen.get(e.getKey());
            if(want == null || !want.key().equals(e.getValue().getChannel().key()))
            {
                e.getValue().close();
                it.remove();
            }
            else if(needsRestart(e.getValue().getMaxQuality(), cfg.effectiveQuality(), e.getKey().equals(fullscreen)))
            {
                // 上限变了：从现在的位置换一路新的，旧的先留着当画面
                VideoSession old = retiring.put(e.getKey(), e.getValue());
                if(old != null)
                    old.close();
                it.remove();
            }
        }
        reportDurations(world);
        long latency = latencyMs(mc);
        for(Map.Entry<BlockPos, TvChannel> e : chosen.entrySet())
        {
            if(!sessions.containsKey(e.getKey()))
            {
                TileEntity te = world.getTileEntity(e.getKey());
                long start = te instanceof TileEntityTV ? ((TileEntityTV) te).clientClock(latency).targetAt(now) : 0;
                sessions.put(e.getKey(), new VideoSession(e.getKey(), e.getValue(), capFor(cfg.effectiveQuality(), e.getKey().equals(fullscreen)), start));
            }
        }
        retiring.entrySet().removeIf(r ->
        {
            VideoSession now2 = sessions.get(r.getKey());
            boolean done = now2 == null || now2.hasPicture() || now2.getStatus() == VideoSession.Status.ERROR;
            if(done)
                r.getValue().close();
            return done;
        });
    }

    /** 取到时长的视频报给服务端（频道还对得上、这个编号没报过时） */
    private void reportDurations(World world)
    {
        reported.keySet().removeIf(p -> !sessions.containsKey(p));
        for(VideoSession session : sessions.values())
        {
            long duration = session.getDurationMs();
            if(duration <= 0)
                continue;
            TileEntity te = world.getTileEntity(session.getPos());
            if(!(te instanceof TileEntityTV))
                continue;
            TileEntityTV tv = (TileEntityTV) te;
            String key = session.getChannel().key();
            if(!key.equals(TvChannel.parse(tv.getCurrentChannel()).key()))
                continue;
            String mark = tv.getClientDurationEpoch() + "|" + key;
            if(mark.equals(reported.get(session.getPos())))
                continue;
            reported.put(session.getPos(), mark);
            PacketHandler.INSTANCE.sendToServer(new MessageTVDuration(session.getPos(), tv.getClientDurationEpoch(), key, duration));
        }
    }

    /** 当前频道是视频，服务端也没关视频功能 */
    private static boolean wantsVideo(TileEntityTV tv)
    {
        if(!tv.isPowered() || tv.isClientVideoOff())
            return false;
        return TvChannel.parse(tv.getCurrentChannel()).isVideo();
    }

    public void closeAll()
    {
        for(VideoSession s : sessions.values())
            s.close();
        sessions.clear();
        for(VideoSession s : retiring.values())
            s.close();
        retiring.clear();
        offSince.clear();
        reported.clear();
        fullscreen = null;
    }

    /**
     * 渲染线程：这台电视（当前频道是视频时）该画什么。
     */
    public Screen screen(TileEntityTV tv)
    {
        TvChannel channel = TvChannel.parse(tv.getCurrentChannel());
        if(channel.getKind() == TvChannel.Kind.UNSUPPORTED)
            return Screen.text("cfm.tv.channel.unsupported", null);
        if(channel.getKind() == TvChannel.Kind.INVALID)
            return Screen.text("cfm.tv.channel.invalid", null);
        if(tv.isClientVideoOff())
            return Screen.text("cfm.tv.video.off", null);
        switch(NativeLibs.getState())
        {
            case UNSUPPORTED:
                return Screen.text("cfm.tv.video.unsupported", null);
            case FAILED:
                return Screen.text(NativeLibs.failedProxyAuth() ? "cfm.tv.video.download_failed_proxy_auth" : "cfm.tv.video.download_failed", null);
            case NOT_STARTED:
            case DOWNLOADING:
            case LOADING:
                return Screen.text("cfm.tv.video.downloading", null);
            default:
                break;
        }
        VideoSession session = sessions.get(tv.getPos());
        VideoSession old = retiring.get(tv.getPos());
        if(session != null && old != null && !session.hasPicture() && old.hasPicture() && session.getStatus() != VideoSession.Status.ERROR)
            session = old;
        if(session == null)
        {
            // 超出同时解码的台数：显示封面
            DynamicTexture cover = CoverCache.get(channel);
            int[] size = CoverCache.size(channel);
            if(cover != null && size != null)
                return new Screen(Screen.Kind.COVER, cover.getGlTextureId(), size[0], size[1], "", null);
            return Screen.noise(null);
        }
        switch(session.getStatus())
        {
            case ERROR:
                return Screen.text(session.getErrorKey(), session);
            case ENDED:
                return Screen.noise(session);
            default:
                if(session.hasPicture())
                    return new Screen(Screen.Kind.VIDEO, session.getTexture(), session.getTexWidth(), session.getTexHeight(), "", session);
                return Screen.noise(session);
        }
    }
}
