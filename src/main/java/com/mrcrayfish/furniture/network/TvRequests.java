package com.mrcrayfish.furniture.network;

import com.mrcrayfish.furniture.compat.RegionUse;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.RateLimiter;
import com.mrcrayfish.furniture.util.RejectLog;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 电视相关客户端包的公共校验（悠然服务器魔改）。只在服务端主线程调用（{@link ServerThread}）。
 * 不合格就拒绝并用 {@link RejectLog} 记一条（同一玩家限频记录）。
 */
public final class TvRequests
{
    /** 遥控器：对着 16 格内的电视按，留点余量 */
    public static final double REMOTE_RANGE = 18.0;
    /** 频道设置界面：同其他方块界面 */
    public static final double SETTINGS_RANGE = 8.0;
    /** 报视频时长：客户端 128 格内才放视频（本地配置“开始播放的距离”的上限），留点余量 */
    public static final double DURATION_RANGE = 144.0;

    /** 遥控器：最多连按 10 次，每秒补 5 次（长按每半秒一步，三个键一起按也够） */
    public static final RateLimiter<UUID> REMOTE = new RateLimiter<>(10, 5.0);
    /** 打开设置、保存频道：最多连续 4 次，每 2 秒补 1 次 */
    public static final RateLimiter<UUID> SETTINGS = new RateLimiter<>(4, 0.5);
    /** 报视频时长：每台电视每次换台只报一次；最多连续 8 次（同时放的几台一起报），每秒补 1 次 */
    public static final RateLimiter<UUID> DURATION = new RateLimiter<>(8, 1.0);

    private TvRequests() {}

    /**
     * 找玩家要操作的电视：限频、区块已加载、方块实体是电视、距离合理、有使用权（出生点保护、冒险模式、Universe Guard）。
     * 不合格返回 null 并记日志。
     */
    @Nullable
    public static TileEntityTV find(EntityPlayerMP player, BlockPos pos, double range, RateLimiter<UUID> limiter, String what)
    {
        return find(player, pos, range, limiter, what, true);
    }

    /** checkUse 为 false 时不查使用权（只是看电视、报时长，不改电视的东西） */
    @Nullable
    public static TileEntityTV find(EntityPlayerMP player, BlockPos pos, double range, RateLimiter<UUID> limiter, String what, boolean checkUse)
    {
        if(!limiter.tryAcquire(player.getUniqueID(), System.currentTimeMillis()))
        {
            RejectLog.log(player, what + " rate limited");
            return null;
        }
        World world = player.getServerWorld();
        if(!world.isBlockLoaded(pos))
        {
            RejectLog.log(player, what + " at unloaded " + pos);
            return null;
        }
        TileEntity tileEntity = world.getTileEntity(pos);
        if(!(tileEntity instanceof TileEntityTV))
        {
            RejectLog.log(player, what + " at " + pos + " which is not a TV");
            return null;
        }
        double dx = player.posX - (pos.getX() + 0.5D);
        double dy = player.posY + player.getEyeHeight() - (pos.getY() + 0.5D);
        double dz = player.posZ - (pos.getZ() + 0.5D);
        if(dx * dx + dy * dy + dz * dz > range * range)
        {
            RejectLog.log(player, what + " at " + pos + " too far away");
            return null;
        }
        if(checkUse && !RegionUse.canUse(world, player, pos))
        {
            RejectLog.log(player, what + " at " + pos + " not allowed here");
            return null;
        }
        return (TileEntityTV) tileEntity;
    }
}
