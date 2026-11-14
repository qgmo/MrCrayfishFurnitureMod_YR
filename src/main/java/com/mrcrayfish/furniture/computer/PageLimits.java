package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;

/**
 * 电脑页面的上限（服主 11-14 定）：页面最大 {@value #MAX_BYTES} 字节、最多 {@value #MAX_ELEMENTS} 个元素、最深 {@value #MAX_DEPTH} 层。
 * 服务端收旁观同步的页面、旁观者渲染前、浏览器渲染前都按它检查；只看大小与结构，不看内容。
 * <p>
 * 先看字节数（超了不解析），再用 jsoup 宽松解析（{@link PageParser}），解析后数元素与层数。
 * jsoup 不处理 DTD 与外部实体，也不展开自定义实体（“十亿笑声”炸不了）；HTML 解析器本身的嵌套层数也有上限。
 */
public final class PageLimits
{
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_ELEMENTS = 2000;
    public static final int MAX_DEPTH = 64;

    public enum Problem
    {
        TOO_LARGE, TOO_MANY_ELEMENTS, TOO_DEEP,
        /** 空页面、解析器出错 */
        MALFORMED
    }

    private PageLimits() {}

    /** 按 XHTML（XML 解析器）检查；不超限返回 null */
    @Nullable
    public static Problem check(byte[] page)
    {
        return check(page, PageParser.Mode.XHTML);
    }

    /** 不超限返回 null */
    @Nullable
    public static Problem check(byte[] page, PageParser.Mode mode)
    {
        try
        {
            PageParser.parse(page, mode);
            return null;
        }
        catch(PageParser.Rejected r)
        {
            return r.problem;
        }
    }
}
