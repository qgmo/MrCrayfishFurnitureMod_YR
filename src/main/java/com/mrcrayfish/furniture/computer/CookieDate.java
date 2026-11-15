package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cookie 的 Expires 日期，按 RFC 6265 第 5.1.1 节的宽松算法解析（各种写法的 HTTP 日期都认），结果是 UTC 毫秒。
 */
public final class CookieDate
{
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    private CookieDate() {}

    /** 解析不了返回 null */
    @Nullable
    public static Long parse(String s)
    {
        int hour = -1, minute = -1, second = -1, day = -1, month = -1, year = -1;
        boolean foundTime = false, foundDay = false, foundMonth = false, foundYear = false;
        for(String token : tokens(s))
        {
            if(!foundTime)
            {
                int[] t = time(token);
                if(t != null)
                {
                    foundTime = true;
                    hour = t[0];
                    minute = t[1];
                    second = t[2];
                    continue;
                }
            }
            if(!foundDay)
            {
                int d = leadingDigits(token, 1, 2);
                if(d >= 0)
                {
                    foundDay = true;
                    day = d;
                    continue;
                }
            }
            if(!foundMonth && token.length() >= 3)
            {
                String m = token.substring(0, 3).toLowerCase(Locale.ROOT);
                int idx = -1;
                for(int i = 0; i < MONTHS.length; i++)
                {
                    if(MONTHS[i].equals(m))
                        idx = i;
                }
                if(idx >= 0)
                {
                    foundMonth = true;
                    month = idx;
                    continue;
                }
            }
            if(!foundYear)
            {
                int y = leadingDigits(token, 2, 4);
                if(y >= 0)
                {
                    foundYear = true;
                    year = y;
                }
            }
        }
        if(foundYear && year >= 70 && year <= 99)
            year += 1900;
        if(foundYear && year >= 0 && year <= 69)
            year += 2000;
        if(!foundTime || !foundDay || !foundMonth || !foundYear)
            return null;
        if(day < 1 || day > 31 || year < 1601 || hour > 23 || minute > 59 || second > 59)
            return null;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setLenient(false);
        c.clear();
        c.set(year, month, day, hour, minute, second);
        try
        {
            return c.getTimeInMillis();
        }
        catch(IllegalArgumentException e)
        {
            // 2 月 30 日之类不存在的日子
            return null;
        }
    }

    /** 按分隔符（RFC 6265 的 delimiter）切开 */
    private static java.util.List<String> tokens(String s)
    {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            boolean delimiter = c == 0x09 || (c >= 0x20 && c <= 0x2F) || (c >= 0x3B && c <= 0x40) || (c >= 0x5B && c <= 0x60) || (c >= 0x7B && c <= 0x7E);
            if(delimiter)
            {
                if(cur.length() > 0)
                {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
            else
            {
                cur.append(c);
            }
        }
        if(cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    /** {@code min}–{@code max} 位数字开头、后面不再是数字时返回那个数，否则 -1 */
    private static int leadingDigits(String t, int min, int max)
    {
        int n = 0;
        while(n < t.length() && Character.isDigit(t.charAt(n)) && t.charAt(n) < 0x80)
            n++;
        if(n < min || n > max)
            return -1;
        return Integer.parseInt(t.substring(0, n));
    }

    /** hms-time：1–2 位 ":" 1–2 位 ":" 1–2 位（后面可以跟非数字） */
    @Nullable
    private static int[] time(String t)
    {
        String[] parts = t.split(":", -1);
        if(parts.length != 3)
            return null;
        int[] out = new int[3];
        for(int i = 0; i < 3; i++)
        {
            String p = parts[i];
            int v = i < 2 ? (p.matches("[0-9]{1,2}") ? Integer.parseInt(p) : -1) : leadingDigits(p, 1, 2);
            if(v < 0)
                return null;
            out[i] = v;
        }
        return out;
    }
}
