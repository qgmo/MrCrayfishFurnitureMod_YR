package com.mrcrayfish.furniture.computer;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest
{
    @Test
    void tokenBucket()
    {
        RateLimiter r = new RateLimiter(2, 4);
        for(int i = 0; i < 4; i++)
            assertTrue(r.tryAcquire(1000, 1));
        assertFalse(r.tryAcquire(1000, 1));
        assertTrue(r.tryAcquire(1500, 1), "半秒补一个");
        assertFalse(r.tryAcquire(1500, 1));
        assertTrue(r.tryAcquire(100_000, 4));
        assertFalse(r.tryAcquire(100_000, 1), "最多攒 4 个");
        assertFalse(r.tryAcquire(50, 1), "时间倒退不补");
    }

    @Test
    void pageUploadsAssembleAndLimit()
    {
        PageUploads u = new PageUploads();
        UUID p = UUID.randomUUID();
        BlockPos pos = new BlockPos(1, 2, 3);
        byte[] full = new byte[PageUploads.CHUNK];
        byte[] tail = new byte[10];
        assertEquals(PageUploads.Outcome.PARTIAL, u.accept(p, pos, 7, 0, 2, full, 0));
        assertEquals(PageUploads.Outcome.COMPLETE, u.accept(p, pos, 7, 1, 2, tail, 0));
        assertEquals(PageUploads.CHUNK + 10, u.take(p).length);
        assertNull(u.take(p));
        // 顺序、编号、大小不对
        assertEquals(PageUploads.Outcome.BAD_CHUNK, u.accept(p, pos, 8, 1, 2, tail, 10));
        assertEquals(PageUploads.Outcome.BAD_CHUNK, u.accept(p, pos, 8, 0, 2, tail, 10), "不是最后一块却不满");
        assertEquals(PageUploads.Outcome.BAD_CHUNK, u.accept(p, pos, 8, 0, PageUploads.MAX_CHUNKS + 1, full, 10));
        assertEquals(PageUploads.Outcome.BAD_CHUNK, u.accept(p, pos, 8, 0, 1, new byte[PageUploads.CHUNK + 1], 10));
        assertEquals(PageUploads.Outcome.PARTIAL, u.accept(p, pos, 9, 0, 2, full, 10));
        assertEquals(PageUploads.Outcome.BAD_CHUNK, u.accept(p, new BlockPos(0, 0, 0), 9, 1, 2, tail, 10), "换了电脑");
        // 频率：每秒 2 页、攒 4 页
        PageUploads v = new PageUploads();
        int ok = 0;
        for(int i = 0; i < 10; i++)
        {
            if(v.accept(p, pos, i, 0, 1, tail, 5000) == PageUploads.Outcome.COMPLETE)
                ok++;
        }
        assertEquals(4, ok);
        assertEquals(PageUploads.Outcome.COMPLETE, v.accept(p, pos, 99, 0, 1, tail, 5600));
    }

    @Test
    void uploadBytesAreLimited()
    {
        PageUploads u = new PageUploads();
        UUID p = UUID.randomUUID();
        BlockPos pos = BlockPos.ORIGIN;
        byte[] full = new byte[PageUploads.CHUNK];
        int accepted = 0;
        for(int i = 0; i < 40; i++)
        {
            PageUploads.Outcome o = u.accept(p, pos, i, 0, 2, full, 0);
            if(o == PageUploads.Outcome.RATE_LIMITED)
                break;
            accepted++;
        }
        assertTrue(accepted <= 10, "字节数也有上限：" + accepted);
    }
}
