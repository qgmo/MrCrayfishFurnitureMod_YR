package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * bilibili 接口返回的解析（纯逻辑，字段照公开文档，悠然服务器魔改）。出错时抛 {@link BiliException}，带给玩家看的语言键。
 */
public final class BiliData
{
    /** 清晰度代码 */
    public static final int QN_360P = 16;
    public static final int QN_480P = 32;
    public static final int QN_720P = 64;
    public static final int QN_1080P = 80;

    /** 视频编码：H.264、H.265、AV1（优先 H.264，FFmpeg 最常见的解码器） */
    static final int CODEC_AVC = 7;
    static final int CODEC_HEVC = 12;
    static final int CODEC_AV1 = 13;

    private BiliData() {}

    public static final class BiliException extends Exception
    {
        private final String key;

        public BiliException(String key, String detail)
        {
            super(detail);
            this.key = key;
        }

        /** 给玩家看的语言键 */
        public String getKey()
        {
            return key;
        }
    }

    /** 一个视频的一 P */
    public static final class VideoInfo
    {
        public final String bvid;
        public final long aid;
        public final long cid;
        public final int page;
        public final String title;
        public final long durationMs;
        @Nullable
        public final String cover;

        VideoInfo(String bvid, long aid, long cid, int page, String title, long durationMs, @Nullable String cover)
        {
            this.bvid = bvid;
            this.aid = aid;
            this.cid = cid;
            this.page = page;
            this.title = title;
            this.durationMs = durationMs;
            this.cover = cover;
        }
    }

    /** 取到的流：MP4（音视频在一个文件里）或 DASH（音视频分开）。每路按顺序是主地址、备用地址 */
    public static final class Stream
    {
        public final boolean dash;
        public final List<URI> video;
        public final List<URI> audio;
        public final int quality;
        public final long durationMs;

        Stream(boolean dash, List<URI> video, List<URI> audio, int quality, long durationMs)
        {
            this.dash = dash;
            this.video = Collections.unmodifiableList(video);
            this.audio = Collections.unmodifiableList(audio);
            this.quality = quality;
            this.durationMs = durationMs;
        }
    }

    /** 接口的通用错误码 → 语言键 */
    static void checkCode(JsonObject root) throws BiliException
    {
        int code = getInt(root, "code", -1);
        if(code == 0)
            return;
        String key;
        switch(code)
        {
            case -404:
            case 62002:
            case 62004:
                key = "cfm.tv.bili.not_found";
                break;
            case 62012:
            case -403:
                key = "cfm.tv.bili.forbidden";
                break;
            case -412:
            case -352:
                key = "cfm.tv.bili.blocked";
                break;
            default:
                key = "cfm.tv.bili.error";
        }
        throw new BiliException(key, "code " + code);
    }

    /** x/web-interface/view 的返回，取第 page P（从 1 开始） */
    public static VideoInfo parseView(JsonObject root, int page) throws BiliException
    {
        checkCode(root);
        JsonObject data = getObject(root, "data");
        if(data == null)
            throw new BiliException("cfm.tv.bili.error", "no data");
        if(!getString(data, "redirect_url", "").isEmpty())
            throw new BiliException("cfm.tv.channel.unsupported", "redirect");
        if(getBoolean(data, "is_upower_exclusive"))
            throw new BiliException("cfm.tv.bili.charging", "upower");
        JsonArray pages = getArray(data, "pages");
        if(pages == null || pages.size() == 0)
            throw new BiliException("cfm.tv.bili.error", "no pages");
        if(page < 1 || page > pages.size())
            throw new BiliException("cfm.tv.bili.no_page", "page " + page + " of " + pages.size());
        JsonElement pe = pages.get(page - 1);
        if(!pe.isJsonObject())
            throw new BiliException("cfm.tv.bili.error", "bad page");
        JsonObject p = pe.getAsJsonObject();
        long cid = getLong(p, "cid", 0);
        if(cid <= 0)
            throw new BiliException("cfm.tv.bili.error", "no cid");
        String title = getString(data, "title", "");
        if(pages.size() > 1)
        {
            String part = getString(p, "part", "");
            if(!part.isEmpty())
                title = title + " - " + part;
        }
        long duration = getLong(p, "duration", getLong(data, "duration", 0));
        String cover = getString(data, "pic", "");
        return new VideoInfo(getString(data, "bvid", ""), getLong(data, "aid", 0), cid, page, title, Math.max(0, duration) * 1000L, cover.isEmpty() ? null : cover);
    }

    /**
     * x/player/wbi/playurl 的返回。有 durl（MP4，只认单个分段）就用 MP4，否则用 DASH：视频选不超过 maxQn 的最高清晰度
     * （优先 H.264），都超过时选最低的；音频选码率最低的那一路（电视用不着高码率）。都没有返回 null。
     */
    @Nullable
    public static Stream parsePlayurl(JsonObject root, int maxQn) throws BiliException
    {
        checkCode(root);
        JsonObject data = getObject(root, "data");
        if(data == null)
            return null;
        long duration = getLong(data, "timelength", 0);
        int quality = getInt(data, "quality", 0);
        JsonArray durl = getArray(data, "durl");
        String format = getString(data, "format", "");
        if(durl != null && durl.size() == 1 && durl.get(0).isJsonObject() && !format.startsWith("flv"))
        {
            JsonObject seg = durl.get(0).getAsJsonObject();
            List<URI> urls = urls(seg, "url", "backup_url");
            if(!urls.isEmpty())
                return new Stream(false, urls, Collections.emptyList(), quality, duration);
        }
        JsonObject dash = getObject(data, "dash");
        if(dash == null)
            return null;
        JsonArray videos = getArray(dash, "video");
        if(videos == null || videos.size() == 0)
            return null;
        JsonObject best = null;
        for(JsonElement e : videos)
        {
            if(!e.isJsonObject())
                continue;
            JsonObject v = e.getAsJsonObject();
            if(best == null || better(v, best, maxQn))
                best = v;
        }
        if(best == null)
            return null;
        List<URI> video = urls(best, "baseUrl", "backupUrl");
        if(video.isEmpty())
            video = urls(best, "base_url", "backup_url");
        if(video.isEmpty())
            return null;
        List<URI> audio = new ArrayList<>();
        JsonArray audios = getArray(dash, "audio");
        if(audios != null)
        {
            JsonObject pick = null;
            for(JsonElement e : audios)
            {
                if(!e.isJsonObject())
                    continue;
                JsonObject a = e.getAsJsonObject();
                if(pick == null || getLong(a, "bandwidth", Long.MAX_VALUE) < getLong(pick, "bandwidth", Long.MAX_VALUE))
                    pick = a;
            }
            if(pick != null)
            {
                audio = urls(pick, "baseUrl", "backupUrl");
                if(audio.isEmpty())
                    audio = urls(pick, "base_url", "backup_url");
            }
        }
        if(duration <= 0)
            duration = getLong(dash, "duration", 0) * 1000L;
        return new Stream(true, video, audio, getInt(best, "id", 0), duration);
    }

    /** a 是否比 b 更合适 */
    private static boolean better(JsonObject a, JsonObject b, int maxQn)
    {
        int qa = getInt(a, "id", 0), qb = getInt(b, "id", 0);
        boolean fitA = qa <= maxQn, fitB = qb <= maxQn;
        if(fitA != fitB)
            return fitA;
        if(qa != qb)
            return fitA ? qa > qb : qa < qb;
        return codecRank(getInt(a, "codecid", 0)) < codecRank(getInt(b, "codecid", 0));
    }

    private static int codecRank(int codec)
    {
        switch(codec)
        {
            case CODEC_AVC:
                return 0;
            case CODEC_HEVC:
                return 1;
            case CODEC_AV1:
                return 2;
            default:
                return 3;
        }
    }

    private static List<URI> urls(JsonObject o, String main, String backup)
    {
        List<URI> list = new ArrayList<>();
        URI u = HostPolicy.upgrade(getString(o, main, null));
        if(u != null)
            list.add(u);
        JsonArray more = getArray(o, backup);
        if(more != null)
        {
            for(JsonElement e : more)
            {
                if(e.isJsonPrimitive())
                {
                    URI b = HostPolicy.upgrade(e.getAsString());
                    if(b != null && !list.contains(b))
                        list.add(b);
                }
            }
        }
        return list;
    }

    /** x/web-interface/nav：返回 {img_key, sub_key}（未登录时 code 是 -101，照样带着） */
    @Nullable
    public static String[] parseNavKeys(JsonObject root)
    {
        JsonObject data = getObject(root, "data");
        JsonObject wbi = data == null ? null : getObject(data, "wbi_img");
        if(wbi == null)
            return null;
        String img = getString(wbi, "img_url", "");
        String sub = getString(wbi, "sub_url", "");
        if(img.isEmpty() || sub.isEmpty())
            return null;
        String imgKey = WbiSigner.keyFromUrl(img), subKey = WbiSigner.keyFromUrl(sub);
        if(!imgKey.matches("[0-9a-zA-Z]{16,64}") || !subKey.matches("[0-9a-zA-Z]{16,64}"))
            return null;
        return new String[]{imgKey, subKey};
    }

    /** x/frontend/finger/spi：返回 {buvid3, buvid4} */
    @Nullable
    public static String[] parseSpi(JsonObject root)
    {
        JsonObject data = getObject(root, "data");
        if(data == null)
            return null;
        String b3 = getString(data, "b_3", ""), b4 = getString(data, "b_4", "");
        if(!b3.matches("[0-9A-Za-z\\-]{8,80}"))
            return null;
        return new String[]{b3, b4.matches("[0-9A-Za-z\\-%=]{8,200}") ? b4 : ""};
    }

    // ---- 取字段（类型不对时用默认值） ----

    @Nullable
    static JsonObject getObject(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    static JsonArray getArray(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static String getString(JsonObject o, String key, String def)
    {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    static long getLong(JsonObject o, String key, long def)
    {
        JsonElement e = o.get(key);
        try
        {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : def;
        }
        catch(NumberFormatException ex)
        {
            return def;
        }
    }

    static int getInt(JsonObject o, String key, int def)
    {
        long v = getLong(o, key, def);
        return v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? def : (int) v;
    }

    static boolean getBoolean(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : "1".equals(e.getAsString()) || "true".equals(e.getAsString()));
    }
}
