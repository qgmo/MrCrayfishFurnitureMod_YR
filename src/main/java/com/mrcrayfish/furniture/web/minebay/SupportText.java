package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;

/**
 * 客服对话里的文字（纯逻辑）：玩家的消息怎么检查、客服的回答怎么清理、回答里转给管理员的留言怎么认。
 * <ul>
 * <li>玩家一条最多 {@value #MAX_MESSAGE} 个字，一行（换行、制表符换成空格），不能有控制字符、U+FFFE / U+FFFF / U+FFFD、落单的代理项；</li>
 * <li>客服的回答：去掉 HTML 标签与控制字符，最多 {@value #MAX_REPLY} 个字（页面上照样转义）；</li>
 * <li>留言：回答里单独一行以 {@value #TICKET_MARK} 开头的是要转给管理员的留言（最多 {@value #MAX_TICKET} 个字），从回答里拿掉。</li>
 * </ul>
 */
public final class SupportText
{
    public static final int MAX_MESSAGE = 200;
    public static final int MAX_REPLY = 300;
    public static final int MAX_TICKET = 200;
    /** 存档里读回来的一条最多多长 */
    static final int MAX_STORED = 400;
    public static final String TICKET_MARK = "【留言】";

    private SupportText() {}

    /** 检查玩家的消息：合格时返回整理过的（去掉首尾空白，连续空白并成一个），空的或不合格返回 null */
    @Nullable
    public static String message(@Nullable String s)
    {
        if(s == null)
            return null;
        StringBuilder b = new StringBuilder();
        for(int i = 0; i < s.length(); )
        {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if(cp == '\n' || cp == '\r' || cp == '\t')
                cp = ' ';
            if(bad(cp))
                return null;
            b.appendCodePoint(cp);
        }
        String t = b.toString().trim().replaceAll(" {2,}", " ");
        if(t.isEmpty() || t.codePointCount(0, t.length()) > MAX_MESSAGE)
            return null;
        return t;
    }

    /** 太长（其余都合格）：给页面说明用 */
    public static boolean tooLong(@Nullable String s)
    {
        if(s == null)
            return false;
        String t = s.trim();
        return t.codePointCount(0, t.length()) > MAX_MESSAGE && message(clip(t, MAX_MESSAGE)) != null;
    }

    static boolean bad(int cp)
    {
        return (cp < 0x20 && cp != '\n' && cp != '\r' && cp != '\t') || (cp >= 0x7F && cp < 0xA0) || cp == 0xFFFE || cp == 0xFFFF || cp == 0xFFFD
                || (cp >= 0xD800 && cp <= 0xDFFF);
    }

    /** 客服的回答拆成：给玩家看的文字、转给管理员的留言（没有为 null） */
    public static final class Reply
    {
        public final String text;
        @Nullable
        public final String ticket;

        Reply(String text, @Nullable String ticket)
        {
            this.text = text;
            this.ticket = ticket;
        }
    }

    public static Reply reply(String raw)
    {
        String ticket = null;
        StringBuilder kept = new StringBuilder();
        for(String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n"))
        {
            String l = line.trim();
            int at = l.indexOf(TICKET_MARK);
            if(at >= 0)
            {
                String before = l.substring(0, at).trim();
                if(ticket == null)
                {
                    String t = clean(l.substring(at + TICKET_MARK.length())).trim();
                    if(t.startsWith("：") || t.startsWith(":"))
                        t = t.substring(1).trim();
                    if(!t.isEmpty())
                        ticket = clip(t, MAX_TICKET);
                }
                if(before.isEmpty())
                    continue;
                l = before;
            }
            if(kept.length() > 0)
                kept.append('\n');
            kept.append(l);
        }
        String text = clean(kept.toString()).replaceAll("\n{2,}", "\n").trim();
        return new Reply(clip(text, MAX_REPLY), ticket);
    }

    /** 去掉 HTML 标签、实体写法里的尖括号、控制字符（换行留着）、格式代码符号 §（留言会进 OP 聊天栏）、Markdown 的粗体星号 */
    static String clean(String s)
    {
        String t = s.replaceAll("(?s)<[^>]*>", "").replace("<", "").replace(">", "").replace("**", "");
        StringBuilder b = new StringBuilder();
        for(int i = 0; i < t.length(); )
        {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if(cp == '\n')
                b.append('\n');
            else if(cp == '\t' || cp == '\r')
                b.append(' ');
            else if(cp != 0xA7 && !bad(cp))
                b.appendCodePoint(cp);
        }
        return b.toString();
    }

    static String clip(String s, int max)
    {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }
}
