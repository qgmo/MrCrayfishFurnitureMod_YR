package com.mrcrayfish.furniture.client.browser;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** 界面上放不下的字：截断加省略号，不超宽，不拆开代理对 */
class TextFitTest
{
    /** 每个字符 6 像素，省略号也是 6 */
    static final ToIntFunction<String> SIX = s -> s.codePointCount(0, s.length()) * 6;

    @Test
    void fitsOrEllipsizes()
    {
        assertEquals("abc", TextFit.fit("abc", 18, SIX));
        assertEquals("ab…", TextFit.fit("abcd", 18, SIX));
        assertTrue(TextFit.overflows("abcd", 18, SIX));
        assertFalse(TextFit.overflows("abc", 18, SIX));
        assertEquals("…", TextFit.fit("abcd", 6, SIX));
        assertEquals("", TextFit.fit("abcd", 5, SIX), "连省略号都放不下");
        assertEquals("", TextFit.fit(null, 100, SIX));
        assertEquals("", TextFit.fit("abc", 0, SIX));
        for(int w = 6; w < 120; w++)
            assertTrue(SIX.applyAsInt(TextFit.fit("https://averyveryverylongdomainnamewithoutanybreaks.yr/", w, SIX)) <= w, "宽 " + w);
    }

    @Test
    void doesNotSplitSurrogatePairs()
    {
        String s = "a😀b😀c";
        for(int w = 6; w < 40; w++)
        {
            String f = TextFit.fit(s, w, SIX);
            for(int i = 0; i < f.length(); i++)
            {
                if(Character.isHighSurrogate(f.charAt(i)))
                    assertTrue(i + 1 < f.length() && Character.isLowSurrogate(f.charAt(i + 1)), f);
            }
        }
    }
}
