package com.mrcrayfish.furniture.util;

import javax.annotation.Nullable;

/**
 * 玩家可写入的文字长度上限（悠然服务器魔改）。客户端界面原来就限制了这些长度，
 * 服务端照同样的上限核对，改过的客户端也写不进更长的内容。
 */
public final class TextLimits
{
    /** 相框、电视的图片地址（客户端输入框上限也是 256）。 */
    public static final int MAX_URL_LENGTH = 256;
    /** 门垫文字（客户端输入框上限也是 100）。 */
    public static final int MAX_DOOR_MAT_LENGTH = 100;

    private TextLimits() {}

    public static boolean urlLengthOk(@Nullable String url)
    {
        return url == null || url.length() <= MAX_URL_LENGTH;
    }

    /**
     * 截到最多 max 个 UTF-16 字符，不把代理对截成半个。
     */
    public static String truncate(@Nullable String text, int max)
    {
        if(text == null || text.length() <= max)
            return text;
        int end = max;
        if(end > 0 && Character.isHighSurrogate(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }
}
