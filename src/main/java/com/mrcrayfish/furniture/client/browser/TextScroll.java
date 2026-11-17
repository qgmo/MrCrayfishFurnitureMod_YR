package com.mrcrayfish.furniture.client.browser;

import java.util.function.ToIntFunction;

/**
 * 单行输入框从第几个字开始显示（悠然服务器魔改）：光标要看得见；框变宽了（比如地址栏右边的“正在排版……”消失）、
 * 后面的字放得下时往回滚，不让开头一直被截着、右边空着（服主 11-17）。
 */
final class TextScroll
{
    private TextScroll() {}

    /**
     * @param text      显示的文字
     * @param cursor    光标位置
     * @param viewStart 上一次从第几个字开始显示
     * @param width     能放字的宽度
     * @param measure   量一段字有多宽
     */
    static int viewStart(String text, int cursor, int viewStart, int width, ToIntFunction<String> measure)
    {
        int start = Math.max(0, Math.min(viewStart, Math.min(cursor, text.length())));
        // 光标在右边看不见：往右滚
        while(start < cursor && measure.applyAsInt(text.substring(start, cursor)) > width)
            start++;
        // 从前一个字开始，剩下的字也全放得下：往左滚回来
        while(start > 0 && measure.applyAsInt(text.substring(start - 1)) <= width)
            start--;
        return start;
    }
}
