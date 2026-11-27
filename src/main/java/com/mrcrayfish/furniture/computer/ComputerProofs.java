package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerProofResult;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import moe.qingu.youran.api.network.identity.OperatorProofs;
import moe.qingu.youran.api.network.identity.OperatorResult;
import moe.qingu.youran.api.network.location.LocationProofs;
import moe.qingu.youran.api.network.location.LocationResult;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 家具电脑替操作者签证明（悠然服务器魔改，服主 11-27）：网站要位置、要一键登录时，浏览器（客户端）请服务端签，服务端在这里核对
 * “他是这台电脑现在的操作者、电脑的网卡现在接着他”，再按装着的外设调 YouranMod 的 {@link LocationProofs#sign} / {@link OperatorProofs#sign}：
 * 地址取自电脑的网卡对象，坐标按电脑方块的位置，玩家给网卡接着的那个人（{@code connectedPlayer()}）。签之前 YouranMod 给他弹系统确认框。
 * 没装对应的模块时不签，告诉网站 {@code error:no_locator} / {@code error:no_identity}。只在服务端主线程上用。
 */
public final class ComputerProofs
{
    public static final int LOCATION = 0;
    public static final int OPERATOR = 1;
    private static final Map<UUID, RateLimiter> LIMIT = new HashMap<>();

    private ComputerProofs() {}

    /** 没装对应的外设时给网站的代号；装了为 null */
    @Nullable
    public static String missingModule(ComputerPeripherals peripherals, int kind)
    {
        if(kind == LOCATION)
            return peripherals.hasLocator() ? null : LocationResult.Status.NO_LOCATOR.code;
        if(kind == OPERATOR)
            return peripherals.hasIdentity() ? null : OperatorResult.Status.NO_IDENTITY.code;
        return LocationResult.Status.BAD_REQUEST.code;
    }

    /** 主线程：客户端请证明；结果（证明全文或 {@code error:代号}）发回给他 */
    public static void request(EntityPlayerMP player, BlockPos pos, int requestId, int kind, String site, String nonce)
    {
        sign(player, pos, kind, site, nonce).whenComplete((v, e) ->
        {
            if(player.connection != null)
                PacketHandler.INSTANCE.sendTo(new MessageComputerProofResult(requestId, v != null ? v : "error:unavailable"), player);
        });
    }

    private static CompletableFuture<String> sign(EntityPlayerMP player, BlockPos pos, int kind, String site, String nonce)
    {
        // 确认框本来就每人同时一个；这里再挡住刷请求的：每人每 2 秒 1 次，最多攒 3 次
        if(!LIMIT.computeIfAbsent(player.getUniqueID(), k -> new RateLimiter(0.5, 3)).tryAcquire(System.currentTimeMillis(), 1))
            return done("error:" + LocationResult.Status.BUSY.code);
        TileEntityComputer computer = ComputerAccess.check(player, pos, true, true, "computer proof");
        if(computer == null)
            return done("error:" + LocationResult.Status.NO_OPERATOR.code);
        ComputerNic nic = computer.getNic();
        if(nic == null || !nic.isPlugged() || nic.connectedPlayer() != player)
        {
            RejectLog.log(player, "computer proof while the computer's network card is not connected to him at " + pos);
            return done("error:" + LocationResult.Status.NO_OPERATOR.code);
        }
        String missing = missingModule(computer.getPeripherals(), kind);
        if(missing != null)
            return done("error:" + missing);
        if(kind == LOCATION)
        {
            return LocationProofs.sign(nic, player.getGameProfile(), computer.getWorld().provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), site, nonce)
                    .thenApply(r -> r.ok() ? r.proof : "error:" + r.status.code);
        }
        return OperatorProofs.sign(nic, player.getGameProfile(), site, nonce).thenApply(r -> r.ok() ? r.proof : "error:" + r.status.code);
    }

    private static CompletableFuture<String> done(String v)
    {
        return CompletableFuture.completedFuture(v);
    }

    /** 下线时丢掉限频状态 */
    public static void forget(UUID player)
    {
        LIMIT.remove(player);
    }
}
