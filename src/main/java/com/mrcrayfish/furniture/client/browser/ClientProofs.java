package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageComputerProof;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端：给“我现在操作的这台电脑”请位置证明、操作者证明（悠然服务器魔改，服主 11-27；取代 YouranMod 原来的 ClientLocation）。
 * 服务端核对他是操作者、电脑装了对应的模块，再由 YouranMod 弹系统确认框、签名。结果是证明全文，拿不到时是 {@code error:代号}。
 * 服务器 {@value #TIMEOUT_MILLIS} 毫秒没回（比确认框的最长时限再多一点）按 {@code error:unavailable} 结束；同一时间最多 {@value #MAX_PENDING} 个。
 * 超时由浏览器每帧调 {@link #sweep} 查。
 */
public final class ClientProofs
{
    public static final long TIMEOUT_MILLIS = 130_000;
    public static final int MAX_PENDING = 4;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private static final class Pending
    {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final long deadline;

        Pending(long deadline)
        {
            this.deadline = deadline;
        }
    }

    private ClientProofs() {}

    /** 请一份证明（{@code kind} 是 {@code ComputerProofs.LOCATION} / {@code OPERATOR}）；{@code site} 是收证明的网站的域名 */
    public static CompletableFuture<String> request(BlockPos computer, int kind, String site, String nonce)
    {
        long now = System.currentTimeMillis();
        sweep(now);
        if(PENDING.size() >= MAX_PENDING)
            return CompletableFuture.completedFuture("error:busy");
        int id = IDS.incrementAndGet();
        Pending p = new Pending(now + TIMEOUT_MILLIS);
        PENDING.put(id, p);
        PacketHandler.INSTANCE.sendToServer(new MessageComputerProof(computer, id, kind, site == null ? "" : site, nonce == null ? "" : nonce));
        return p.future;
    }

    /** 服务端的回复（Netty 线程） */
    public static void onResult(int requestId, String value)
    {
        Pending p = PENDING.remove(requestId);
        if(p != null)
            p.future.complete(value == null || value.isEmpty() ? "error:unavailable" : value);
    }

    /** 到时没回的按不可用结束 */
    public static void sweep(long now)
    {
        Iterator<Map.Entry<Integer, Pending>> it = PENDING.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<Integer, Pending> e = it.next();
            if(now >= e.getValue().deadline)
            {
                it.remove();
                e.getValue().future.complete("error:unavailable");
            }
        }
    }
}
