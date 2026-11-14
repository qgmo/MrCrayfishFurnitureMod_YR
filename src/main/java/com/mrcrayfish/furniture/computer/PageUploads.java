package com.mrcrayfish.furniture.computer;

import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 操作者上传页面（服务端，主线程）：页面压缩后可能超过一个客户端包的上限（32 KiB），所以分块传，这里按玩家拼起来。
 * 每个玩家同时只拼一份；块要按顺序来、大小与总数都有上限；按玩家限频（每秒几页、每秒多少字节）。
 * 纯逻辑：时间由调用方给，校验通过与否由返回值表示（调用方记日志）。
 */
public final class PageUploads
{
    public static final int CHUNK = 24 * 1024;
    public static final int MAX_CHUNKS = (PageSnapshot.MAX_COMPRESSED + CHUNK - 1) / CHUNK;
    /** 每秒最多开始几页（攒满可以连着 4 页） */
    static final double PAGES_PER_SECOND = 2;
    static final double PAGE_BURST = 4;
    /** 每秒最多多少字节 */
    static final double BYTES_PER_SECOND = 192 * 1024;
    static final double BYTE_BURST = 256 * 1024;

    public enum Outcome
    {
        /** 收下了，还没收齐 */
        PARTIAL,
        /** 收齐了，用 {@link #take} 取 */
        COMPLETE,
        /** 块不对（编号、大小、顺序、和正在拼的不是同一份） */
        BAD_CHUNK,
        /** 超过频率 */
        RATE_LIMITED
    }

    private static final class Pending
    {
        BlockPos pos;
        int id;
        int count;
        int next;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
    }

    private static final class Limits
    {
        final RateLimiter pages = new RateLimiter(PAGES_PER_SECOND, PAGE_BURST);
        final RateLimiter bytes = new RateLimiter(BYTES_PER_SECOND, BYTE_BURST);
    }

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Limits> limits = new HashMap<>();
    private final Map<UUID, byte[]> done = new HashMap<>();

    public Outcome accept(UUID player, BlockPos pos, int id, int index, int count, byte[] chunk, long nowMillis)
    {
        if(count < 1 || count > MAX_CHUNKS || index < 0 || index >= count || chunk.length > CHUNK || (index < count - 1 && chunk.length != CHUNK))
            return reject(player, Outcome.BAD_CHUNK);
        Limits l = limits.computeIfAbsent(player, k -> new Limits());
        if(!l.bytes.tryAcquire(nowMillis, chunk.length))
            return reject(player, Outcome.RATE_LIMITED);
        Pending p = pending.get(player);
        if(index == 0)
        {
            if(!l.pages.tryAcquire(nowMillis, 1))
                return reject(player, Outcome.RATE_LIMITED);
            p = new Pending();
            p.pos = pos.toImmutable();
            p.id = id;
            p.count = count;
            pending.put(player, p);
        }
        else if(p == null || p.id != id || p.count != count || !p.pos.equals(pos) || p.next != index)
        {
            return reject(player, Outcome.BAD_CHUNK);
        }
        p.data.write(chunk, 0, chunk.length);
        p.next++;
        if(p.data.size() > PageSnapshot.MAX_COMPRESSED)
            return reject(player, Outcome.BAD_CHUNK);
        if(p.next < p.count)
            return Outcome.PARTIAL;
        pending.remove(player);
        done.put(player, p.data.toByteArray());
        return Outcome.COMPLETE;
    }

    private Outcome reject(UUID player, Outcome o)
    {
        pending.remove(player);
        return o;
    }

    /** 取走刚收齐的那份（压缩的） */
    @Nullable
    public byte[] take(UUID player)
    {
        return done.remove(player);
    }

    /** 玩家下线时清掉 */
    public void forget(UUID player)
    {
        pending.remove(player);
        limits.remove(player);
        done.remove(player);
    }
}
