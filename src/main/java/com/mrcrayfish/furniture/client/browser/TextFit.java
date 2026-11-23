package com.mrcrayfish.furniture.client.browser;

import java.util.function.ToIntFunction;

/** 界面上一行放不下的字：截断并在末尾加省略号（纯 Java，量宽度的函数由界面给；完整的字由调用的地方放进悬停提示） */
final class TextFit
{
    static final String ELLIPSIS = "…";

    private TextFit() {}

    /** 放得下原样返回；放不下截到加上省略号正好放得下（宽度不够放省略号时返回空串）。不拆开代理对 */
    static String fit(String s, int width, ToIntFunction<String> measure)
    {
        if(s == null || s.isEmpty() || width <= 0)
            return "";
        if(measure.applyAsInt(s) <= width)
            return s;
        int room = width - measure.applyAsInt(ELLIPSIS);
        if(room < 0)
            return "";
        int lo = 0, hi = s.length();
        // 二分：最长的、量出来不超过 room 的前缀
        while(lo < hi)
        {
            int mid = (lo + hi + 1) / 2;
            if(measure.applyAsInt(s.substring(0, mid)) <= room)
                lo = mid;
            else
                hi = mid - 1;
        }
        if(lo > 0 && Character.isHighSurrogate(s.charAt(lo - 1)))
            lo--;
        return s.substring(0, lo) + ELLIPSIS;
    }

    /** 放不下了没有（要不要给悬停提示） */
    static boolean overflows(String s, int width, ToIntFunction<String> measure)
    {
        return s != null && !s.isEmpty() && measure.applyAsInt(s) > width;
    }
}
