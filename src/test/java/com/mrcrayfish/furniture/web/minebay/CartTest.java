package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CartTest
{
    @Test
    void addMergesAndRespectsLimits()
    {
        Cart c = new Cart();
        assertEquals(Cart.Change.OK, c.add("config:a", 3, 64));
        assertEquals(Cart.Change.OK, c.add("config:a", 4, 64));
        assertEquals(7, c.quantityOf("config:a"));
        assertEquals(1, c.lines().size(), "同一件合成一行");
        assertEquals(Cart.Change.TOO_MANY, c.add("config:a", 58, 64));
        assertEquals(7, c.quantityOf("config:a"), "超了不改");
        assertEquals(Cart.Change.OK, c.add("config:a", 57, 64));
        assertEquals(64, c.quantityOf("config:a"));
        assertEquals(Cart.Change.BAD_QUANTITY, c.add("config:b", 0, 64));
        assertEquals(Cart.Change.BAD_QUANTITY, c.add("config:b", -1, 64));
        assertEquals(Cart.Change.BAD_QUANTITY, c.add("config:b", 65, 64));
        // 商品自己的上限更小
        assertEquals(Cart.Change.BAD_QUANTITY, c.add("mod:floppy", 2, 1));
        assertEquals(Cart.Change.OK, c.add("mod:floppy", 1, 1));
        assertEquals(Cart.Change.TOO_MANY, c.add("mod:floppy", 1, 1));
        // 商品上限写错（0、超过 64）时按 1 与 64 夹住
        assertEquals(Cart.Change.OK, c.add("mod:weird", 1, 0));
        assertEquals(Cart.Change.TOO_MANY, c.add("mod:weird", 1, 0));
        assertEquals(Cart.Change.BAD_QUANTITY, c.add("mod:big", 65, 1000));
    }

    @Test
    void lineCountIsCapped()
    {
        Cart c = new Cart();
        for(int i = 0; i < Cart.MAX_LINES; i++)
            assertEquals(Cart.Change.OK, c.add("config:" + i, 1, 64));
        assertEquals(Cart.Change.FULL, c.add("config:new", 1, 64));
        assertEquals(Cart.MAX_LINES, c.lines().size());
        // 已有的那一种照样能加
        assertEquals(Cart.Change.OK, c.add("config:0", 1, 64));
        assertEquals(Cart.MAX_LINES + 1, c.totalQuantity());
    }

    @Test
    void setRemoveAndBought()
    {
        Cart c = new Cart();
        c.add("config:a", 5, 64);
        c.add("config:b", 2, 64);
        assertEquals(Cart.Change.NOT_IN_CART, c.set("config:x", 1, 64));
        assertEquals(Cart.Change.BAD_QUANTITY, c.set("config:a", 0, 64));
        assertEquals(5, c.quantityOf("config:a"));
        assertEquals(Cart.Change.OK, c.set("config:a", 9, 64));
        assertEquals(11, c.totalQuantity());
        Map<String, Integer> bought = new LinkedHashMap<>();
        bought.put("config:a", 9);
        c.removeBought(bought);
        assertEquals(0, c.quantityOf("config:a"));
        assertEquals(1, c.lines().size(), "只拿掉买走的");
        c.removeBought(Collections.singletonMap("config:b", 1));
        assertEquals(1, c.quantityOf("config:b"), "购物车里后来又加了的留下");
        assertTrue(c.remove("config:b"));
        assertFalse(c.remove("config:b"));
        assertTrue(c.isEmpty());
    }

    @Test
    void savesAndLoadsAndRejectsBadData()
    {
        Cart c = new Cart();
        c.add("config:a", 5, 64);
        c.add("youranscp:floppy_scan", 1, 1);
        Cart back = Cart.read(c.write());
        assertEquals(5, back.quantityOf("config:a"));
        assertEquals(1, back.quantityOf("youranscp:floppy_scan"));
        assertEquals("config:a", back.lines().get(0).id, "顺序不变");

        NBTTagList bad = new NBTTagList();
        bad.appendTag(entry("config:ok", 999));
        bad.appendTag(entry("../../x", 1));
        bad.appendTag(entry("config:zero", 0));
        bad.appendTag(entry("config:ok", 3));
        for(int i = 0; i < 100; i++)
            bad.appendTag(entry("config:n" + i, 1));
        Cart r = Cart.read(bad);
        assertEquals(Cart.MAX_QUANTITY, r.quantityOf("config:ok"), "份数截到上限");
        assertEquals(0, r.quantityOf("../../x"));
        assertEquals(0, r.quantityOf("config:zero"));
        assertEquals(Cart.MAX_LINES, r.lines().size(), "种数截到上限");
    }

    private static NBTTagCompound entry(String id, int n)
    {
        NBTTagCompound t = new NBTTagCompound();
        t.setString("id", id);
        t.setInteger("n", n);
        return t;
    }
}
