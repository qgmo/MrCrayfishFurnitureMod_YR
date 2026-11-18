package com.mrcrayfish.furniture.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 设置界面 ↔ 搜索界面的来回（服主 11-18：搜索改成单独的界面） */
class TvSearchLinkTest
{
    @Test
    void goingToSearchKeepsTheContainer()
    {
        TvSearchLink link = new TvSearchLink();
        assertEquals(-1, link.field());
        assertTrue(link.begin(1, 3));
        assertEquals(1, link.field());
        // displayGuiScreen(搜索界面) 让设置界面 onGuiClosed：不走客户端的容器关闭
        assertEquals(TvSearchLink.Close.KEEP_FOR_SEARCH, link.settingsClosed());
        assertTrue(link.searching());
        assertEquals(1, link.field());
        // 搜索时收到的保存结果照样收下
        assertTrue(link.acceptsResult(false));
        // 点了结果、回到设置界面
        link.returned();
        assertFalse(link.searching());
        assertEquals(-1, link.field());
        // 之后真的关掉设置界面（Esc、服务端关窗口）：照常关容器
        assertEquals(TvSearchLink.Close.CLOSE_CONTAINER, link.settingsClosed());
        assertFalse(link.acceptsResult(false));
        assertTrue(link.acceptsResult(true));
    }

    @Test
    void plainCloseIsAPlainClose()
    {
        TvSearchLink link = new TvSearchLink();
        assertEquals(TvSearchLink.Close.CLOSE_CONTAINER, link.settingsClosed());
        assertFalse(link.searching());
    }

    @Test
    void searchClosedByTheServerDetaches()
    {
        TvSearchLink link = new TvSearchLink();
        assertTrue(link.begin(0, 3));
        link.settingsClosed();
        // 电视被拆、走远：服务端关了容器，客户端直接关掉搜索界面
        link.searchClosedElsewhere();
        assertFalse(link.searching());
        assertFalse(link.acceptsResult(false));
        assertEquals(-1, link.field());
    }

    @Test
    void badOrRepeatedRequests()
    {
        TvSearchLink link = new TvSearchLink();
        assertFalse(link.begin(-1, 3));
        assertFalse(link.begin(3, 3));
        assertTrue(link.begin(2, 3));
        // 已经在去的路上：不再开第二个
        assertFalse(link.begin(0, 3));
        // 换界面被拦住了：当作没去，下次照常
        link.cancel();
        assertEquals(TvSearchLink.Close.CLOSE_CONTAINER, link.settingsClosed());
        assertTrue(link.begin(0, 3));
        assertEquals(TvSearchLink.Close.KEEP_FOR_SEARCH, link.settingsClosed());
        assertFalse(link.begin(1, 3));
    }
}
