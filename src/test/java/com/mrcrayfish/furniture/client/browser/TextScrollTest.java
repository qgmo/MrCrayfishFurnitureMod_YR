package com.mrcrayfish.furniture.client.browser;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 输入框滚动（服主 11-17：“正在排版”消失后地址没有回到从头显示） */
class TextScrollTest
{
    /** 每个字 6 像素宽 */
    static final ToIntFunction<String> MONO = s -> s.length() * 6;

    @Test
    void scrollsBackWhenTheBoxGetsWider()
    {
        String url = "pay.yr/?c=tools";
        // 窄的时候（排版提示占了地方）：光标在末尾，开头被截掉
        int narrow = TextScroll.viewStart(url, url.length(), 0, 60, MONO);
        assertEquals(5, narrow);
        // 提示消失、框变宽：整串放得下，回到从头显示
        assertEquals(0, TextScroll.viewStart(url, url.length(), narrow, 200, MONO));
    }

    @Test
    void scrollsBackOnlyAsFarAsFits()
    {
        String s = "abcdefghijklmnopqrst"; // 20 个字，120 像素
        // 宽 90 像素放得下 15 个字，光标在末尾：从第 5 个开始
        assertEquals(5, TextScroll.viewStart(s, s.length(), 12, 90, MONO));
    }

    @Test
    void keepsCursorVisible()
    {
        String s = "abcdefghijklmnopqrst";
        assertEquals(3, TextScroll.viewStart(s, 3, 10, 30, MONO), "光标在左边看不见：滚到光标处（后面放不下，不往回滚）");
        assertEquals(15, TextScroll.viewStart(s, 20, 0, 30, MONO), "光标在右边看不见：往右滚");
    }

    @Test
    void emptyAndShort()
    {
        assertEquals(0, TextScroll.viewStart("", 0, 3, 50, MONO));
        assertEquals(0, TextScroll.viewStart("abc", 3, 2, 50, MONO));
    }
}
