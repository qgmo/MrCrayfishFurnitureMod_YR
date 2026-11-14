package com.mrcrayfish.furniture.computer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 会话恢复：存什么、限多少、服务端怎么查、存档来回 */
class BrowserHistoryTest
{
    @Test
    void keepsStorableEntriesAndMovesIndex()
    {
        // 错误页（敲错的地址）、空地址不存；当前那条跟着挪
        BrowserHistory h = BrowserHistory.of(Arrays.asList("about:home", "ftp://x", "http://test.yr/", "", "https://minebay.yr/?p=2"), 4);
        assertEquals(Arrays.asList("about:home", "http://test.yr/", "https://minebay.yr/?p=2"), h.urls);
        assertEquals(2, h.index);
        assertEquals("https://minebay.yr/?p=2", h.current());
        BrowserHistory back = BrowserHistory.of(Arrays.asList("about:home", "ftp://x", "http://test.yr/"), 1);
        assertEquals(0, back.index, "当前那条不能存时落到它前面最近的一条");
        assertTrue(BrowserHistory.of(Collections.singletonList("javascript:alert(1)"), 0).isEmpty());
    }

    @Test
    void capsEntriesAndLength()
    {
        List<String> many = new ArrayList<>();
        for(int i = 0; i < 50; i++)
            many.add("http://test.yr/" + i);
        BrowserHistory h = BrowserHistory.of(many, 45);
        assertEquals(BrowserHistory.MAX_ENTRIES, h.urls.size());
        assertEquals("http://test.yr/45", h.current(), "当前那条留着");
        assertEquals("http://test.yr/18", h.urls.get(0), "先丢最旧的");
        // 很长的网址：总长不超过上限
        StringBuilder sb = new StringBuilder("http://test.yr/");
        while(sb.length() < UrlInput.MAX_LENGTH - 10)
            sb.append('a');
        List<String> longs = new ArrayList<>();
        for(int i = 0; i < 20; i++)
            longs.add(sb.toString() + i);
        BrowserHistory l = BrowserHistory.of(longs, 19);
        int total = 0;
        for(String u : l.urls)
            total += u.length();
        assertTrue(total <= BrowserHistory.MAX_TOTAL_CHARS);
        assertEquals(longs.get(19), l.current());
    }

    @Test
    void serverRejectsBadReports()
    {
        assertNotNull(BrowserHistory.checked(Arrays.asList("about:home", "http://test.yr/"), 1));
        assertNull(BrowserHistory.checked(Arrays.asList("about:home", "http://test.yr/"), 2), "下标越界");
        assertNull(BrowserHistory.checked(Collections.singletonList("file:///etc/passwd"), 0));
        assertNull(BrowserHistory.checked(Collections.singletonList("http://Test.yr"), 0), "没规范化的不收");
        List<String> many = new ArrayList<>();
        for(int i = 0; i <= BrowserHistory.MAX_ENTRIES; i++)
            many.add("http://test.yr/" + i);
        assertNull(BrowserHistory.checked(many, 0));
        // 编码来回、乱数据
        BrowserHistory h = BrowserHistory.checked(Arrays.asList("about:home", "https://minebay.yr/"), 0);
        assertEquals(h, BrowserHistory.decode(h.encode()));
        assertNull(BrowserHistory.decode(new byte[]{0, 0, 0, 0, 0, 1, 0, 3, 'f', 't', 'p'}));
        assertNull(BrowserHistory.decode(new byte[3]));
        assertNull(BrowserHistory.decode(new byte[BrowserHistory.MAX_ENCODED + 1]));
    }

    @Test
    void nbtRoundTripAndTamperedSaves()
    {
        BrowserHistory h = BrowserHistory.checked(Arrays.asList("about:home", "http://test.yr/", "https://minebay.yr/"), 1);
        NBTTagCompound tag = new NBTTagCompound();
        h.writeToNBT(tag);
        assertEquals(h, BrowserHistory.readFromNBT(tag));
        assertTrue(BrowserHistory.readFromNBT(new NBTTagCompound()).isEmpty(), "旧存档没有会话");
        // 被改过的存档：当作没有
        NBTTagCompound bad = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagString("file:///etc/passwd"));
        bad.setTag("History", list);
        bad.setInteger("HistoryIndex", 0);
        assertTrue(BrowserHistory.readFromNBT(bad).isEmpty());
    }
}
