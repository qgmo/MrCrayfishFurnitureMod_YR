package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrcrayfish.furniture.tv.TvChannel;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 频道设置界面的搜索（纯逻辑，悠然服务器魔改，服主 11-18）：解析 bilibili 分类搜索接口
 * {@code x/web-interface/wbi/search/type}（{@code search_type=video} / {@code live_room}）的返回，数字按中文、英文习惯缩写。
 * 接口与参数参考 PiliPlus（GPL-3.0，只借鉴思路）的 {@code lib/http/search.dart}，11-18 用 curl 实测核对：
 * <ul>
 * <li>视频：{@code data.result[]} 的 {@code bvid}、{@code title}（带 {@code <em class="keyword">} 高亮标签）、{@code author}、
 * {@code play}（播放量）、{@code pic}（封面，常是不带协议的 {@code //i0.hdslb.com/…}，也见过 {@code archive.biliimg.com}）；</li>
 * <li>直播间：{@code data.result[]} 的 {@code roomid}、{@code title}、{@code uname}、{@code online}（PiliPlus 显示为“围观”人数）、
 * {@code user_cover}（主播设的封面，没有时用 {@code cover} 关键帧）；</li>
 * <li>{@code data.numPages} 总页数；被风控时 {@code code} 还是 0，但 {@code data} 里只有 {@code v_voucher}。</li>
 * </ul>
 */
public final class BiliSearch
{
    public static final int PAGE_SIZE = 20;
    /** 标题最多留多少字 */
    static final int MAX_TITLE = 80;
    static final int MAX_NAME = 40;

    private static final Pattern TAG = Pattern.compile("<[^>]{0,200}>");

    public enum Type
    {
        VIDEO("video", "cfm.tv.search.type_video"),
        LIVE("live_room", "cfm.tv.search.type_live");

        public final String apiName;
        public final String langKey;

        Type(String apiName, String langKey)
        {
            this.apiName = apiName;
            this.langKey = langKey;
        }
    }

    /** 一条结果：点了以后填进频道格的写法、标题、UP 主 / 主播、播放量 / 围观人数、封面 */
    public static final class Item
    {
        public final Type type;
        public final String channel;
        public final String title;
        public final String author;
        public final long count;
        @Nullable
        public final String cover;

        Item(Type type, String channel, String title, String author, long count, @Nullable String cover)
        {
            this.type = type;
            this.channel = channel;
            this.title = title;
            this.author = author;
            this.count = count;
            this.cover = cover;
        }
    }

    /** 一页结果 */
    public static final class Page
    {
        public final List<Item> items;
        public final int page;
        public final int numPages;

        Page(List<Item> items, int page, int numPages)
        {
            this.items = Collections.unmodifiableList(items);
            this.page = page;
            this.numPages = numPages;
        }
    }

    private BiliSearch() {}

    /** 被风控（要人机验证）：接口 code 是 0，但只回了 v_voucher */
    public static boolean isChallenge(JsonObject root)
    {
        JsonObject data = BiliData.getObject(root, "data");
        return data != null && data.has("v_voucher") && !data.has("result");
    }

    public static Page parse(JsonObject root, Type type, int page) throws BiliData.BiliException
    {
        BiliData.checkCode(root);
        if(isChallenge(root))
            throw new BiliData.BiliException("cfm.tv.search.blocked", "v_voucher");
        JsonObject data = BiliData.getObject(root, "data");
        if(data == null)
            throw new BiliData.BiliException("cfm.tv.bili.error", "no data");
        int numPages = Math.max(0, BiliData.getInt(data, "numPages", 0));
        JsonArray result = BiliData.getArray(data, "result");
        if(result == null)
        {
            // search_type=live 时 result 是 {live_room: [...], live_user: [...]}
            JsonObject r = BiliData.getObject(data, "result");
            result = r == null ? null : BiliData.getArray(r, "live_room");
        }
        List<Item> items = new ArrayList<>();
        if(result != null)
        {
            for(JsonElement e : result)
            {
                if(!e.isJsonObject())
                    continue;
                Item item = type == Type.VIDEO ? video(e.getAsJsonObject()) : live(e.getAsJsonObject());
                if(item != null && items.size() < PAGE_SIZE * 2)
                    items.add(item);
            }
        }
        return new Page(items, page, numPages);
    }

    @Nullable
    private static Item video(JsonObject o)
    {
        String bvid = BiliData.getString(o, "bvid", "");
        TvChannel c = TvChannel.parse(bvid);
        if(c.getKind() != TvChannel.Kind.BILIBILI || c.getBvid() == null)
            return null;
        return new Item(Type.VIDEO, c.getBvid(), text(BiliData.getString(o, "title", ""), MAX_TITLE), text(BiliData.getString(o, "author", ""), MAX_NAME),
                Math.max(0, BiliData.getLong(o, "play", 0)), cover(BiliData.getString(o, "pic", "")));
    }

    @Nullable
    private static Item live(JsonObject o)
    {
        long room = BiliData.getLong(o, "roomid", 0);
        if(room <= 0 || room > TvChannel.MAX_ROOM)
            return null;
        String cover = cover(BiliData.getString(o, "user_cover", ""));
        if(cover == null)
            cover = cover(BiliData.getString(o, "cover", ""));
        return new Item(Type.LIVE, "live" + room, text(BiliData.getString(o, "title", ""), MAX_TITLE), text(BiliData.getString(o, "uname", ""), MAX_NAME),
                Math.max(0, BiliData.getLong(o, "online", 0)), cover);
    }

    /** 去掉高亮标签等 HTML 标签、实体转回字符、去掉控制字符和 §，限长 */
    static String text(String s, int max)
    {
        String plain = Danmaku.unescape(TAG.matcher(s).replaceAll(""));
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for(int i = 0; i < plain.length() && sb.length() < max; i++)
        {
            char c = plain.charAt(i);
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
        if(!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1)))
            out = out.substring(0, out.length() - 1);
        return out;
    }

    /** 封面地址：补上协议、改成 https，不在封面白名单里的不要 */
    @Nullable
    static String cover(String url)
    {
        if(url == null || url.isEmpty())
            return null;
        URI u = HostPolicy.upgrade(url);
        if(u == null || !HostPolicy.allowed(u, HostPolicy.Purpose.COVER, false))
            return null;
        return u.toString();
    }

    /**
     * 播放量、人数的缩写，按界面语言：简体 1.2万、3.4亿，繁体 1.2萬、3.4億（不到一万照写）；其余语言 1.2K、3.4M、5.6B（不到一千照写）。
     * 保留一位小数（向下取），末尾的 .0 去掉。
     */
    public static String compact(long n, @Nullable String languageCode)
    {
        if(n < 0)
            n = 0;
        String lang = languageCode == null ? "" : languageCode.toLowerCase(Locale.ROOT);
        if(lang.startsWith("zh"))
        {
            boolean traditional = lang.equals("zh_tw") || lang.equals("zh_hk");
            if(n < 10_000)
                return Long.toString(n);
            if(n < 100_000_000)
                return oneDecimal(n, 10_000) + (traditional ? "萬" : "万");
            return oneDecimal(n, 100_000_000) + (traditional ? "億" : "亿");
        }
        if(n < 1_000)
            return Long.toString(n);
        if(n < 1_000_000)
            return oneDecimal(n, 1_000) + "K";
        if(n < 1_000_000_000)
            return oneDecimal(n, 1_000_000) + "M";
        return oneDecimal(n, 1_000_000_000) + "B";
    }

    /** 向下取一位小数（不进位，免得 9999 写成 1.0万） */
    private static String oneDecimal(long n, long unit)
    {
        long tenths = n * 10 / unit;
        long whole = tenths / 10, frac = tenths % 10;
        return frac == 0 ? Long.toString(whole) : String.format(Locale.ROOT, "%d.%d", whole, frac);
    }
}
