package com.mrcrayfish.furniture.client.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕（纯逻辑，悠然服务器魔改，服主 11-14：“尝试做一下”）：解析弹幕 XML、按视频进度排版。
 * 只认滚动（模式 1–3）、底部（4）、顶部（5）三种，高级弹幕、代码弹幕不管。
 * XML 用正则逐条取，不走 XML 解析器（不碰外部实体之类）。
 */
public final class Danmaku
{
    /** 最多留多少条（按时间均匀保留） */
    public static final int MAX_COMMENTS = 3000;
    /** 一条最多多少字 */
    public static final int MAX_TEXT = 40;
    /** 滚动弹幕从右走到左用多久 */
    public static final long SCROLL_MS = 8000L;
    /** 顶部、底部弹幕停多久 */
    public static final long FIXED_MS = 4000L;

    public static final int MODE_SCROLL = 1;
    public static final int MODE_BOTTOM = 4;
    public static final int MODE_TOP = 5;

    private static final Pattern D = Pattern.compile("<d p=\"([^\"]{1,200})\">([^<]{0,500})</d>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos);");

    public static final class Comment
    {
        public final long timeMs;
        public final int mode;
        public final int color;
        public final String text;

        public Comment(long timeMs, int mode, int color, String text)
        {
            this.timeMs = timeMs;
            this.mode = mode;
            this.color = color;
            this.text = text;
        }
    }

    /** 排好位置的一条：lane 是第几行，width 是文字宽 */
    public static final class Placed
    {
        public final Comment comment;
        public final int lane;
        public final int width;

        Placed(Comment comment, int lane, int width)
        {
            this.comment = comment;
            this.lane = lane;
            this.width = width;
        }
    }

    /** 某一时刻要画的一条：x、y 是画布上的左上角 */
    public static final class Visible
    {
        public final String text;
        public final int color;
        public final float x;
        public final float y;

        Visible(String text, int color, float x, float y)
        {
            this.text = text;
            this.color = color;
            this.x = x;
            this.y = y;
        }
    }

    private Danmaku() {}

    public static List<Comment> parse(String xml)
    {
        List<Comment> list = new ArrayList<>();
        Matcher m = D.matcher(xml);
        while(m.find())
        {
            String[] p = m.group(1).split(",");
            if(p.length < 4)
                continue;
            try
            {
                double sec = Double.parseDouble(p[0]);
                int mode = Integer.parseInt(p[1]);
                long color = Long.parseLong(p[3]);
                if(!(sec >= 0) || sec > 360000)
                    continue;
                if(mode >= 1 && mode <= 3)
                    mode = MODE_SCROLL;
                else if(mode != MODE_TOP && mode != MODE_BOTTOM)
                    continue;
                String text = clean(unescape(m.group(2)));
                if(text.isEmpty())
                    continue;
                list.add(new Comment((long) (sec * 1000), mode, (int) (color & 0xFFFFFF), text));
            }
            catch(NumberFormatException e)
            {
                // 跳过这一条
            }
        }
        list.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        if(list.size() > MAX_COMMENTS)
        {
            List<Comment> kept = new ArrayList<>(MAX_COMMENTS);
            double step = list.size() / (double) MAX_COMMENTS;
            for(int i = 0; i < MAX_COMMENTS; i++)
                kept.add(list.get((int) (i * step)));
            list = kept;
        }
        return list;
    }

    static String unescape(String s)
    {
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while(m.find())
        {
            String e = m.group(1);
            String r;
            switch(e)
            {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                default:
                    try
                    {
                        int cp = e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
                    }
                    catch(NumberFormatException ex)
                    {
                        r = "";
                    }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 去掉控制字符和 Minecraft 的格式符 §，空白并成一个，限制长度 */
    static String clean(String s)
    {
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for(int i = 0; i < s.length() && sb.length() < MAX_TEXT; i++)
        {
            char c = s.charAt(i);
            if(c == '§' || Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.FORMAT)
                continue;
            if(Character.isWhitespace(c))
            {
                space = sb.length() > 0;
                continue;
            }
            if(space)
            {
                sb.append(' ');
                space = false;
            }
            sb.append(c);
        }
        String out = sb.toString();
        // 不留下半个代理对
        if(!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1)))
            out = out.substring(0, out.length() - 1);
        return out;
    }

    /**
     * 排版：在宽 canvasWidth、可用行数 lanes 的画布上给每条分一行；滚动弹幕要等同一行前一条的尾巴完全进场、
     * 且不会被追上才放；放不下的这条就不显示（自然控制密度）。width 是量文字宽的函数。
     */
    public static List<Placed> layout(List<Comment> comments, int canvasWidth, int lanes, ToIntFunction<String> width)
    {
        List<Placed> placed = new ArrayList<>();
        if(lanes <= 0 || canvasWidth <= 0)
            return placed;
        Placed[] scroll = new Placed[lanes];
        long[] topUntil = new long[lanes];
        long[] bottomUntil = new long[lanes];
        for(Comment c : comments)
        {
            int w = Math.max(1, width.applyAsInt(c.text));
            if(c.mode == MODE_SCROLL)
            {
                for(int lane = 0; lane < lanes; lane++)
                {
                    if(scroll[lane] == null || scrollFits(scroll[lane], c, w, canvasWidth))
                    {
                        Placed p = new Placed(c, lane, w);
                        scroll[lane] = p;
                        placed.add(p);
                        break;
                    }
                }
            }
            else
            {
                long[] until = c.mode == MODE_TOP ? topUntil : bottomUntil;
                for(int lane = 0; lane < lanes; lane++)
                {
                    if(until[lane] <= c.timeMs)
                    {
                        until[lane] = c.timeMs + FIXED_MS;
                        placed.add(new Placed(c, lane, w));
                        break;
                    }
                }
            }
        }
        return placed;
    }

    /** 前一条 prev 在同一行时，next 能不能在它出发的时刻放进去 */
    static boolean scrollFits(Placed prev, Comment next, int nextWidth, int canvasWidth)
    {
        long dt = next.timeMs - prev.comment.timeMs;
        if(dt >= SCROLL_MS)
            return true;
        // 前一条在 dt 时走过的距离（速度 = (画布宽 + 自身宽) / 时长）
        double prevSpeed = (canvasWidth + prev.width) / (double) SCROLL_MS;
        double nextSpeed = (canvasWidth + nextWidth) / (double) SCROLL_MS;
        double travelled = prevSpeed * dt;
        if(travelled < prev.width + 4)
            return false;
        if(nextSpeed <= prevSpeed)
            return true;
        // 后一条更快：到前一条离场时不能追上
        double prevLeft = SCROLL_MS - dt;
        double gap = travelled - prev.width;
        return (nextSpeed - prevSpeed) * prevLeft <= gap;
    }

    /**
     * 时刻 timeMs 要画的弹幕。placed 按时间排序；lineHeight 是行高，canvasHeight 用来放底部弹幕。
     */
    public static List<Visible> visibleAt(List<Placed> placed, long timeMs, int canvasWidth, int canvasHeight, int lineHeight)
    {
        if(placed.isEmpty())
            return Collections.emptyList();
        List<Visible> out = new ArrayList<>();
        int from = firstAtOrAfter(placed, timeMs - SCROLL_MS);
        for(int i = from; i < placed.size(); i++)
        {
            Placed p = placed.get(i);
            long dt = timeMs - p.comment.timeMs;
            if(dt < 0)
                break;
            if(p.comment.mode == MODE_SCROLL)
            {
                if(dt >= SCROLL_MS)
                    continue;
                float x = (float) (canvasWidth - (canvasWidth + p.width) * (dt / (double) SCROLL_MS));
                out.add(new Visible(p.comment.text, p.comment.color, x, p.lane * lineHeight));
            }
            else
            {
                if(dt >= FIXED_MS)
                    continue;
                float x = (canvasWidth - p.width) / 2.0F;
                float y = p.comment.mode == MODE_TOP ? p.lane * lineHeight : canvasHeight - (p.lane + 1) * lineHeight;
                out.add(new Visible(p.comment.text, p.comment.color, x, y));
            }
        }
        return out;
    }

    private static int firstAtOrAfter(List<Placed> placed, long timeMs)
    {
        int lo = 0, hi = placed.size();
        while(lo < hi)
        {
            int mid = (lo + hi) >>> 1;
            if(placed.get(mid).comment.timeMs < timeMs)
                lo = mid + 1;
            else
                hi = mid;
        }
        return lo;
    }
}
