package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class StockTest
{
    static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    /** 北京时间 2026-11-15 12:00 */
    static final long T0 = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, BJ).toInstant().toEpochMilli();
    static final long DAY = Stock.DAY_MS;

    /** 按月上限、每天补：一开始满的；卖完就没了；每天补上限的 1/30，连续补；不超上限 */
    @Test
    void dailyRefill()
    {
        Stock.Spec spec = new Stock.Spec(30, Stock.Rule.DAILY);
        Stock.State s = Stock.advance(null, spec, T0);
        assertEquals(30, Stock.available(s), "一开始是满的");
        assertTrue(Stock.take(s, 30));
        assertEquals(0, Stock.available(s));
        assertFalse(Stock.take(s, 1), "卖完了");
        Stock.advance(s, spec, T0 + DAY / 2);
        assertEquals(0, Stock.available(s), "半天补半份，还不够一份");
        Stock.advance(s, spec, T0 + DAY);
        assertEquals(1, Stock.available(s), "一天补 1/30 = 1 份");
        Stock.advance(s, spec, T0 + 10 * DAY);
        assertEquals(10, Stock.available(s));
        Stock.advance(s, spec, T0 + 100 * DAY);
        assertEquals(30, Stock.available(s), "不超过上限");
    }

    /** 工具盘的量：BASIC 盘每月 14 张，每天补 14/30 张，连续补（不是每天整数） */
    @Test
    void dailyRefillFractional()
    {
        Stock.Spec spec = new Stock.Spec(14, Stock.Rule.DAILY);
        Stock.State s = Stock.advance(null, spec, T0);
        assertTrue(Stock.take(s, 14));
        Stock.advance(s, spec, T0 + 2 * DAY);
        assertEquals(0, Stock.available(s), "两天补 0.93 份");
        Stock.advance(s, spec, T0 + 3 * DAY);
        assertEquals(1, Stock.available(s), "三天补 1.4 份");
        // 分很多次小步补，和一次补到同一时刻一样
        Stock.State t = Stock.advance(null, spec, T0);
        Stock.take(t, 14);
        for(int h = 1; h <= 30 * 24; h++)
            Stock.advance(t, spec, T0 + h * 3600_000L);
        assertEquals(14, Stock.available(t), "30 天正好补满");
        assertTrue(Stock.untilNext(t, T0) == 0, "满了不用等");
        Stock.State u = Stock.advance(null, spec, T0);
        Stock.take(u, 14);
        long wait = Stock.untilNext(u, T0);
        Stock.advance(u, spec, T0 + wait);
        assertEquals(1, Stock.available(u), "等 untilNext 那么久正好多一份");
        assertTrue(Stock.describeWait(Stock.advance(null, spec, T0), T0).isEmpty());
    }

    /** 按月整批重置：月中卖完不补；到下个月 1 日（北京时间）补满；不按天补 */
    @Test
    void monthlyReset()
    {
        Stock.Spec spec = new Stock.Spec(2, Stock.Rule.MONTHLY);
        Stock.State s = Stock.advance(null, spec, T0);
        assertEquals(2, Stock.available(s));
        assertTrue(Stock.take(s, 2));
        Stock.advance(s, spec, T0 + 10 * DAY);
        assertEquals(0, Stock.available(s), "11-25，同一个月不补");
        long dec1 = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, BJ).toInstant().toEpochMilli();
        Stock.advance(s, spec, dec1 - 1);
        assertEquals(0, Stock.available(s), "11-30 23:59:59.999 还没补");
        assertEquals(1, Stock.untilNext(s, dec1 - 1));
        Stock.advance(s, spec, dec1);
        assertEquals(2, Stock.available(s), "12-01 0 点补满");
        assertTrue(Stock.take(s, 1));
        Stock.advance(s, spec, dec1 + 20 * DAY);
        assertEquals(1, Stock.available(s), "不按天补");
        assertTrue(Stock.describeWait(s, dec1).contains("1 月 1 日补满"));
        // 跨年
        long jan1 = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, BJ).toInstant().toEpochMilli();
        Stock.take(s, 1);
        Stock.advance(s, spec, jan1);
        assertEquals(2, Stock.available(s));
    }

    /** 上限变了截到新上限；规则变了从满的重来 */
    @Test
    void specChanges()
    {
        Stock.State s = Stock.advance(null, new Stock.Spec(30, Stock.Rule.DAILY), T0);
        Stock.take(s, 5);
        Stock.advance(s, new Stock.Spec(10, Stock.Rule.DAILY), T0);
        assertEquals(10, Stock.available(s));
        Stock.take(s, 10);
        Stock.State r = Stock.advance(s, new Stock.Spec(10, Stock.Rule.MONTHLY), T0);
        assertEquals(10, Stock.available(r), "规则变了从满的开始");
        assertThrows(IllegalArgumentException.class, () -> new Stock.Spec(0, Stock.Rule.DAILY));
        assertThrows(IllegalArgumentException.class, () -> new Stock.Spec(Stock.MAX_PER_MONTH + 1, Stock.Rule.DAILY));
        assertEquals(Stock.Rule.MONTHLY, Stock.Rule.of(" Monthly "));
        assertNull(Stock.Rule.of("weekly"));
    }

    /** 付了钱的照扣，不够时扣到 0（不让付了钱的落空）；take 不收负数 */
    @Test
    void takeReserved()
    {
        Stock.Spec spec = new Stock.Spec(3, Stock.Rule.DAILY);
        Stock.State s = Stock.advance(null, spec, T0);
        Stock.takeReserved(s, 5);
        assertEquals(0, Stock.available(s));
        assertFalse(Stock.take(Stock.advance(null, spec, T0), -1));
    }

    /** 存档：写了再读一样；坏的不收 */
    @Test
    void saveAndLoad()
    {
        Stock.Spec spec = new Stock.Spec(14, Stock.Rule.DAILY);
        Stock.State s = Stock.advance(null, spec, T0);
        Stock.take(s, 13);
        NBTTagCompound t = s.write();
        Stock.State r = Stock.State.read(t);
        assertNotNull(r);
        assertEquals(1, Stock.available(r));
        Stock.advance(r, spec, T0 + 30 * Stock.DAY_MS);
        assertEquals(14, Stock.available(r));
        NBTTagCompound bad = s.write();
        bad.setString("rule", "weekly");
        assertNull(Stock.State.read(bad));
        NBTTagCompound nan = s.write();
        nan.setDouble("level", Double.NaN);
        assertNull(Stock.State.read(nan));
    }
}
