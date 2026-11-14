package com.mrcrayfish.furniture.web;

/** 写 XHTML 时转义文字 */
public final class Html
{
    private Html() {}

    public static String escape(String s)
    {
        if(s == null)
            return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch(c)
            {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default:
                    // XML 1.0 不许的字符去掉：控制字符、U+FFFE / U+FFFF、落单的代理项（否则整页解析失败）
                    if(Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                    {
                        sb.append(c).append(s.charAt(++i));
                    }
                    else if((c >= 0x20 || c == '\n' || c == '\t' || c == '\r') && c != 0xFFFE && c != 0xFFFF && !Character.isSurrogate(c))
                        sb.append(c);
            }
        }
        return sb.toString();
    }
}
