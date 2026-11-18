package com.mrcrayfish.furniture.web.minebay;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class OrdersTest
{
    static Orders.Order order(long no)
    {
        return new Orders.Order(no, 1_700_000_000_000L + no, Arrays.asList(
                new Orders.Item("config:a", "minecraft:hardened_clay", 16, null, "陶瓦", 2, "minecraft:emerald", 2),
                new Orders.Item("mod:b", "minecraft:paper", 1, "软盘", "软盘", 1, "minecraft:diamond", 3),
                new Orders.Item("config:c", "minecraft:saddle", 1, null, "鞍", 1, "minecraft:emerald", 4)), "ord_" + no);
    }

    @Test
    void keepsNewestUpToTheCap()
    {
        Orders o = new Orders();
        for(int i = 1; i <= Orders.MAX_ORDERS + 7; i++)
            o.add(order(i));
        assertEquals(Orders.MAX_ORDERS, o.list().size());
        assertEquals(Orders.MAX_ORDERS + 7, o.list().get(0).number, "最新的在前");
        assertNull(o.find(7), "最早的不再保留");
        assertNotNull(o.find(8));
    }

    @Test
    void totalsAndSaving()
    {
        Orders o = new Orders();
        o.add(order(42));
        assertEquals(Long.valueOf(6), o.list().get(0).totals().get("minecraft:emerald"));
        assertEquals(Long.valueOf(3), o.list().get(0).totals().get("minecraft:diamond"));
        Orders back = Orders.read(o.write());
        Orders.Order b = back.find(42);
        assertNotNull(b);
        assertEquals(1_700_000_000_042L, b.time);
        assertEquals(3, b.items.size());
        assertNull(b.items.get(0).name);
        assertEquals("软盘", b.items.get(1).name);
        assertEquals(16, b.items.get(0).count);
        assertEquals(Collections.singletonList(42L), Arrays.asList(back.list().get(0).number));
    }

    /** 用翻译键起名的商品：订单里记着键（浏览器按玩家语言显示）和服务端的中文名；读回时不合写法的键丢掉、名字照旧 */
    @Test
    void nameKeySaved()
    {
        Orders o = new Orders();
        o.add(new Orders.Order(7, 1_700_000_000_007L, Arrays.asList(
                new Orders.Item("youranmod:tool_disk/basic", "youranmod:floppy_disk", 1, "BASIC 软盘", "youranmod.minebay.tool_disk.basic.name", "BASIC 软盘", 1,
                        "minecraft:emerald", 16),
                new Orders.Item("mod:x", "minecraft:paper", 1, "坏键", "bad key<>", "坏键", 1, "minecraft:emerald", 1)), "ord_7"));
        Orders.Order back = Orders.read(o.write()).find(7);
        assertNotNull(back);
        assertEquals("youranmod.minebay.tool_disk.basic.name", back.items.get(0).nameKey);
        assertEquals("BASIC 软盘", back.items.get(0).name);
        assertNull(back.items.get(1).nameKey);
        assertEquals("坏键", back.items.get(1).name);
    }
}
