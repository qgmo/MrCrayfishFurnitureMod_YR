package com.mrcrayfish.furniture.computer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * application/x-www-form-urlencoded（WHATWG URL 标准的写法，UTF-8）：表单提交、网站读表单都用它。
 * 编码：字母数字与 {@code *-._} 原样，空格写成 {@code +}，其余按 UTF-8 字节写 {@code %XX}。
 */
public final class FormEncoding
{
    private FormEncoding() {}

    public static String encode(List<Map.Entry<String, String>> fields)
    {
        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, String> f : fields)
        {
            if(sb.length() > 0)
                sb.append('&');
            component(sb, f.getKey());
            sb.append('=');
            component(sb, f.getValue());
        }
        return sb.toString();
    }

    private static void component(StringBuilder sb, String s)
    {
        for(byte b : s.getBytes(StandardCharsets.UTF_8))
        {
            int c = b & 0xFF;
            if((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '*' || c == '-' || c == '.' || c == '_')
                sb.append((char) c);
            else if(c == ' ')
                sb.append('+');
            else
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
    }

    /** 解析；坏的 %XX 原样保留，不认识的字节按 UTF-8 替换字符 */
    public static List<Map.Entry<String, String>> decode(String s)
    {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if(s == null || s.isEmpty())
            return out;
        for(String part : s.split("&"))
        {
            if(part.isEmpty())
                continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.add(new AbstractMap.SimpleImmutableEntry<>(unescape(k), unescape(v)));
        }
        return out;
    }

    static String unescape(String s)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if(c == '+')
            {
                out.write(' ');
            }
            else if(c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0)
            {
                out.write(Character.digit(s.charAt(i + 1), 16) << 4 | Character.digit(s.charAt(i + 2), 16));
                i += 2;
            }
            else
            {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if(Character.isHighSurrogate(c) && i + 1 < s.length())
                {
                    b = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static Map.Entry<String, String> field(String name, String value)
    {
        return new AbstractMap.SimpleImmutableEntry<>(name, value);
    }
}
