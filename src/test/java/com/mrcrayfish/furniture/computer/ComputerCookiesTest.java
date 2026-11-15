package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 电脑的 Cookie 存在服务端：按位置与电脑编号存、存档读写、上限；方块更新发给附近的人的 NBT 里没有 */
class ComputerCookiesTest
{
    @Test
    void storedPerComputerAndSurvivesSave()
    {
        long now = System.currentTimeMillis();
        ComputerCookies d = new ComputerCookies(ComputerCookies.NAME);
        UUID id = UUID.randomUUID();
        CookieJar jar = d.jar(5L, id);
        assertTrue(jar.isEmpty());
        jar.set("sid=abc; Max-Age=3600", "https://portal.yr/", now);
        jar.set("lang=zh", "https://portal.yr/", now);
        assertTrue(d.put(5L, id, jar));
        assertEquals("sid=abc; lang=zh", d.jar(5L, id).header("https://portal.yr/", CookieJar.Context.USER, now));
        // 同一位置换了一台电脑（编号不同）：看不到原来的
        assertTrue(d.jar(5L, UUID.randomUUID()).isEmpty());
        // 存档（关服再开）
        NBTTagCompound tag = d.writeToNBT(new NBTTagCompound());
        ComputerCookies loaded = new ComputerCookies(ComputerCookies.NAME);
        loaded.readFromNBT(tag);
        assertEquals("sid=abc; lang=zh", loaded.jar(5L, id).header("https://portal.yr/", CookieJar.Context.USER, now + 1000));
        // 拆掉
        loaded.remove(5L);
        assertTrue(loaded.jar(5L, id).isEmpty());
        // 清空就删条目
        jar.clear();
        d.put(5L, id, jar);
        assertEquals(0, d.computers());
    }

    @Test
    void computerLimit()
    {
        ComputerCookies d = new ComputerCookies(ComputerCookies.NAME);
        long now = System.currentTimeMillis();
        for(int i = 0; i < ComputerCookies.MAX_COMPUTERS; i++)
        {
            CookieJar j = new CookieJar();
            j.set("a=1", "http://a.yr/", now);
            assertTrue(d.put(i, UUID.randomUUID(), j));
        }
        CookieJar j = new CookieJar();
        j.set("a=1", "http://a.yr/", now);
        assertFalse(d.put(-1L, UUID.randomUUID(), j), "到上限后新电脑存不了");
        assertEquals(ComputerCookies.MAX_COMPUTERS, d.computers());
    }

    @Test
    void syncTagHasNoPrivateData()
    {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("CookieJarId", UUID.randomUUID());
        tag.setString("History", "x");
        tag.setInteger("HistoryIndex", 0);
        tag.setBoolean("PowerOn", true);
        TileEntityComputer.stripPrivate(tag);
        assertFalse(tag.hasUniqueId("CookieJarId"));
        assertFalse(tag.hasKey("History"));
        assertFalse(tag.hasKey("HistoryIndex"));
        assertTrue(tag.getBoolean("PowerOn"));
    }
}
