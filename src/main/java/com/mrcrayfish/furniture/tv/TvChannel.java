package com.mrcrayfish.furniture.tv;

import com.mrcrayfish.furniture.util.TextLimits;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电视频道的写法（纯逻辑，两端共用，悠然服务器魔改）。一个频道是下面几种之一：
 * <ul>
 * <li>GIF 网址（照旧，客户端只认白名单域名里的 .gif）；</li>
 * <li>bilibili 视频：{@code BV…}、{@code av…}，可带 {@code ?p=N} 指定分 P（不写是第 1 P）；
 * 也认 {@code https://www.bilibili.com/video/BV…?p=N} 这样的播放页地址（只取视频号和 p，别的参数不管）；</li>
 * <li>bilibili 直播间（服主 11-18）：{@code https://live.bilibili.com/房间号}（也认 {@code /h5/房间号}、{@code /blanc/房间号}，
 * 查询串不管），或短写法 {@code live房间号}；房间号可以是短号，客户端用接口换成真实房间号；</li>
 * <li>番剧、影视（{@code ep…}、{@code ss…}、{@code /bangumi/play/…}）：认得但暂不支持。</li>
 * </ul>
 * 服务端只存玩家填的原文，客户端各自解析。
 */
public final class TvChannel
{
    public enum Kind
    {
        /** 空 */
        EMPTY,
        /** GIF 网址（http / https） */
        GIF,
        /** bilibili 视频 */
        BILIBILI,
        /** bilibili 直播间 */
        LIVE,
        /** 番剧、影视：暂不支持 */
        UNSUPPORTED,
        /** 写法不对 */
        INVALID
    }

    /** 分 P 的上限（bilibili 一个稿件最多几百 P） */
    public static final int MAX_PAGE = 1000;

    /** BV 号：BV + 10 位 base58（第 1 位固定是 1） */
    private static final Pattern BV = Pattern.compile("(?i:BV)(1[1-9A-HJ-NP-Za-km-z]{9})");
    private static final Pattern AV = Pattern.compile("(?i:av)([0-9]{1,16})");
    private static final Pattern EP_SS = Pattern.compile("(?i:ep|ss|md)[0-9]{1,16}");
    /** 直播间短写法：live + 房间号 */
    private static final Pattern LIVE_SHORT = Pattern.compile("(?i:live)([0-9]{1,12})");
    /** 直播间地址的路径：/房间号、/h5/房间号、/blanc/房间号 */
    private static final Pattern LIVE_PATH = Pattern.compile("/(?:h5/|blanc/)?([0-9]{1,12})/?");
    /** 房间号上限（现在的房间号是八九位数，留足余量） */
    public static final long MAX_ROOM = 999_999_999_999L;
    private static final Pattern PAGE = Pattern.compile("(?:^|&)p=([0-9]{1,4})(?:&|$)");

    private final Kind kind;
    private final String raw;
    @Nullable
    private final String bvid;
    private final long aid;
    private final int page;
    /** 直播间号（玩家填的，可能是短号）；不是直播间时为 0 */
    private final long room;

    private TvChannel(Kind kind, String raw, @Nullable String bvid, long aid, int page)
    {
        this(kind, raw, bvid, aid, page, 0);
    }

    private TvChannel(Kind kind, String raw, @Nullable String bvid, long aid, int page, long room)
    {
        this.kind = kind;
        this.raw = raw;
        this.bvid = bvid;
        this.aid = aid;
        this.page = page;
        this.room = room;
    }

    /** 直播间号（玩家填的，可能是短号，客户端用接口换成真实房间号）；不是直播间时为 0 */
    public long getRoom()
    {
        return room;
    }

    public boolean isLive()
    {
        return kind == Kind.LIVE;
    }

    /** 要取流放的（视频或直播）：客户端交给解码，用电按视频算 */
    public boolean isStream()
    {
        return kind == Kind.BILIBILI || kind == Kind.LIVE;
    }

    public Kind getKind()
    {
        return kind;
    }

    public String getRaw()
    {
        return raw;
    }

    /** BV 号（含 "BV" 前缀）；用 av 号写的频道为 null */
    @Nullable
    public String getBvid()
    {
        return bvid;
    }

    /** av 号；用 BV 号写的频道为 0 */
    public long getAid()
    {
        return aid;
    }

    /** 分 P，从 1 开始 */
    public int getPage()
    {
        return page;
    }

    public boolean isVideo()
    {
        return kind == Kind.BILIBILI;
    }

    /** 同一个视频同一 P（或同一个直播间）的键（客户端缓存、判断换没换台用）；不是视频、直播时就是原文 */
    public String key()
    {
        if(kind == Kind.LIVE)
            return "live#" + room;
        if(kind != Kind.BILIBILI)
            return raw;
        return (bvid != null ? bvid : "av" + aid) + "#" + page;
    }

    public static TvChannel parse(@Nullable String input)
    {
        String raw = input == null ? "" : input.trim();
        if(raw.isEmpty())
            return new TvChannel(Kind.EMPTY, raw, null, 0, 1);
        if(raw.length() > TextLimits.MAX_URL_LENGTH || hasControl(raw))
            return invalid(raw);

        String lower = raw.toLowerCase(Locale.ROOT);
        if(lower.startsWith("http://") || lower.startsWith("https://"))
            return parseUrl(raw);
        Matcher lm = LIVE_SHORT.matcher(raw);
        if(lm.matches())
            return live(raw, lm.group(1));
        return parseId(raw, null);
    }

    private static TvChannel parseUrl(String raw)
    {
        URI uri;
        try
        {
            uri = new URI(raw);
        }
        catch(URISyntaxException e)
        {
            return invalid(raw);
        }
        String host = uri.getHost();
        if(host == null)
            return invalid(raw);
        host = host.toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if(host.equals("live.bilibili.com"))
        {
            Matcher m = LIVE_PATH.matcher(path);
            return m.matches() ? live(raw, m.group(1)) : invalid(raw);
        }
        if(host.equals("bilibili.com") || host.endsWith(".bilibili.com"))
        {
            if(path.startsWith("/bangumi/"))
                return new TvChannel(Kind.UNSUPPORTED, raw, null, 0, 1);
            if(path.startsWith("/video/"))
            {
                String id = path.substring("/video/".length());
                if(id.endsWith("/"))
                    id = id.substring(0, id.length() - 1);
                return parseId(id, uri.getRawQuery()).withRaw(raw);
            }
            return invalid(raw);
        }
        // 别的网址都按 GIF 网址处理（能不能放由客户端照白名单和文件类型判断）
        return new TvChannel(Kind.GIF, raw, null, 0, 1);
    }

    private static TvChannel live(String raw, String digits)
    {
        long room;
        try
        {
            room = Long.parseLong(digits);
        }
        catch(NumberFormatException e)
        {
            return invalid(raw);
        }
        if(room <= 0 || room > MAX_ROOM)
            return invalid(raw);
        return new TvChannel(Kind.LIVE, raw, null, 0, 1, room);
    }

    /** 解析 "BV…"、"av…"（可带 "?p=N"）；query 是播放页地址里的查询串 */
    private static TvChannel parseId(String text, @Nullable String query)
    {
        String id = text;
        String q = query;
        int mark = id.indexOf('?');
        if(mark >= 0)
        {
            if(q != null)
                return invalid(text);
            q = id.substring(mark + 1);
            id = id.substring(0, mark);
        }
        int page = 1;
        if(q != null && !q.isEmpty())
        {
            Matcher pm = PAGE.matcher(q);
            if(pm.find())
            {
                page = Integer.parseInt(pm.group(1));
                if(page < 1 || page > MAX_PAGE)
                    return invalid(text);
            }
            else if(query == null)
            {
                // 直接写的 "BV…?xxx" 只认 p 参数
                return invalid(text);
            }
        }
        Matcher bv = BV.matcher(id);
        if(bv.matches())
            return new TvChannel(Kind.BILIBILI, text, "BV" + bv.group(1), 0, page);
        Matcher av = AV.matcher(id);
        if(av.matches())
        {
            long aid;
            try
            {
                aid = Long.parseLong(av.group(1));
            }
            catch(NumberFormatException e)
            {
                return invalid(text);
            }
            // av 号上限 2^51（BV 号编码的范围）
            if(aid <= 0 || aid >= (1L << 51))
                return invalid(text);
            return new TvChannel(Kind.BILIBILI, text, null, aid, page);
        }
        if(EP_SS.matcher(id).matches())
            return new TvChannel(Kind.UNSUPPORTED, text, null, 0, 1);
        return invalid(text);
    }

    private TvChannel withRaw(String newRaw)
    {
        return new TvChannel(kind, newRaw, bvid, aid, page, room);
    }

    private static TvChannel invalid(String raw)
    {
        return new TvChannel(Kind.INVALID, raw, null, 0, 1);
    }

    private static boolean hasControl(String s)
    {
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if(c < 0x20 || c == 0x7F || c == '§' || Character.isWhitespace(c))
                return true;
        }
        return false;
    }

    /**
     * 服务端核对玩家填的一个频道：空、GIF 网址、bilibili 视频、直播间都收；写法不对、番剧影视拒绝。
     *
     * @return null 表示可以；否则是给玩家看的语言键
     */
    @Nullable
    public static String rejectReason(@Nullable String input)
    {
        if(input != null && input.length() > TextLimits.MAX_URL_LENGTH)
            return "cfm.tv.channel.too_long";
        TvChannel channel = parse(input);
        switch(channel.kind)
        {
            case EMPTY:
            case GIF:
            case BILIBILI:
            case LIVE:
                return null;
            case UNSUPPORTED:
                return "cfm.tv.channel.unsupported";
            default:
                return "cfm.tv.channel.invalid";
        }
    }
}
