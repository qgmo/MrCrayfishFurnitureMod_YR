package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;

/**
 * 发往 bilibili 接口的 Cookie 头（悠然服务器魔改）：玩家自己配的 cookie（可选）+ 匿名的 buvid3 / buvid4（没配时由
 * {@code x/frontend/finger/spi} 取，不涉及登录）。cookie 原文只在这里拼进请求头，不进日志、异常信息。
 */
public final class TvCookies
{
    public static final int MAX_LENGTH = 4096;

    private static volatile String buvid3 = "";
    private static volatile String buvid4 = "";

    private TvCookies() {}

    /** 去掉换行、控制字符（防止拼出多余的请求头），去掉首尾空白，太长的截断 */
    public static String sanitize(@Nullable String cookie)
    {
        if(cookie == null)
            return "";
        StringBuilder sb = new StringBuilder(Math.min(cookie.length(), MAX_LENGTH));
        for(int i = 0; i < cookie.length() && sb.length() < MAX_LENGTH; i++)
        {
            char c = cookie.charAt(i);
            if(c >= 0x20 && c < 0x7F)
                sb.append(c);
        }
        String s = sb.toString().trim();
        if(s.regionMatches(true, 0, "cookie:", 0, 7))
            s = s.substring(7).trim();
        return s;
    }

    /** 拼出 Cookie 头的值；没有任何 cookie 时返回空串 */
    public static String header(String userCookie)
    {
        String user = sanitize(userCookie);
        StringBuilder sb = new StringBuilder(user);
        if(!buvid3.isEmpty() && !contains(user, "buvid3"))
            append(sb, "buvid3=" + buvid3);
        if(!buvid4.isEmpty() && !contains(user, "buvid4"))
            append(sb, "buvid4=" + buvid4);
        return sb.toString();
    }

    static boolean contains(String cookie, String name)
    {
        for(String part : cookie.split(";"))
        {
            String p = part.trim();
            int eq = p.indexOf('=');
            if(eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    private static void append(StringBuilder sb, String pair)
    {
        if(sb.length() > 0)
            sb.append("; ");
        sb.append(pair);
    }

    public static boolean hasBuvid()
    {
        return !buvid3.isEmpty();
    }

    static void setBuvid(String b3, String b4)
    {
        buvid3 = b3;
        buvid4 = b4;
    }
}
