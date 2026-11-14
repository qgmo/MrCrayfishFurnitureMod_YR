package com.mrcrayfish.furniture.client.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * bilibili Web 接口的 WBI 签名（纯逻辑，按公开文档的步骤自己写的，悠然服务器魔改）：
 * <ol>
 * <li>img_key + sub_key 按固定的 64 位重排表取字符，截前 32 位得到 mixin_key；</li>
 * <li>参数加上 wts（秒），按键名升序，值去掉 {@code !'()*} 后做百分号编码（大写十六进制，空格 %20），
 * 拼上 mixin_key 取 MD5，就是 w_rid。</li>
 * </ol>
 */
public final class WbiSigner
{
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private WbiSigner() {}

    /** 从 nav 返回的图片地址里取文件名（去扩展名） */
    public static String keyFromUrl(String url)
    {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    public static String mixinKey(String imgKey, String subKey)
    {
        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder(32);
        for(int index : MIXIN_KEY_ENC_TAB)
        {
            if(index < raw.length())
                sb.append(raw.charAt(index));
            if(sb.length() == 32)
                break;
        }
        return sb.toString();
    }

    /** 签好的查询串（参数按键名排序，末尾是 w_rid） */
    public static String sign(Map<String, String> params, long wts, String mixinKey)
    {
        TreeMap<String, String> sorted = new TreeMap<>();
        for(Map.Entry<String, String> e : params.entrySet())
        {
            sorted.put(e.getKey(), filter(e.getValue()));
        }
        sorted.put("wts", Long.toString(wts));
        StringBuilder query = new StringBuilder();
        for(Map.Entry<String, String> e : sorted.entrySet())
        {
            if(query.length() > 0)
                query.append('&');
            query.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        String wRid = md5Hex(query + mixinKey);
        return query + "&w_rid=" + wRid;
    }

    private static String filter(String value)
    {
        StringBuilder sb = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if("!'()*".indexOf(c) < 0)
                sb.append(c);
        }
        return sb.toString();
    }

    /** 同 JavaScript 的 encodeURIComponent：不编码 A-Z a-z 0-9 - _ . ! ~ * ' ( )，其余 UTF-8 后 %XX（大写） */
    static String encode(String s)
    {
        StringBuilder sb = new StringBuilder();
        for(byte b : s.getBytes(StandardCharsets.UTF_8))
        {
            int c = b & 0xFF;
            if((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0)
                sb.append((char) c);
            else
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return sb.toString();
    }

    static String md5Hex(String s)
    {
        try
        {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for(byte b : d)
            {
                sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return sb.toString();
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
