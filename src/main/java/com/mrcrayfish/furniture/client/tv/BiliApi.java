package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.tv.TvChannel;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调 bilibili 接口（悠然服务器魔改，只在客户端、后台线程用）。接口参数照公开文档：
 * 视频信息 {@code x/web-interface/view}，取流 {@code x/player/wbi/playurl}（WBI 签名，口令来自 {@code x/web-interface/nav}），
 * 匿名 buvid {@code x/frontend/finger/spi}，弹幕 {@code comment.bilibili.com/<cid>.xml}。
 * 结果在内存里缓存一段时间；失败不自动高频重试（由调用方决定什么时候再试）。
 */
public final class BiliApi
{
    /** 接口返回的大小上限 */
    private static final long MAX_JSON = 2L * 1024 * 1024;
    private static final long MAX_DANMAKU = 8L * 1024 * 1024;
    private static final long MAX_COVER = 4L * 1024 * 1024;
    /** 视频信息缓存 30 分钟，WBI 口令 1 小时 */
    private static final long VIEW_TTL = 30 * 60_000L;
    private static final long NAV_TTL = 60 * 60_000L;

    private static final Map<String, Cached<BiliData.VideoInfo>> VIEWS = new ConcurrentHashMap<>();
    private static volatile String mixinKey;
    private static volatile long mixinKeyAt;
    private static volatile boolean spiTried;

    private static final class Cached<T>
    {
        final T value;
        final long at;

        Cached(T value, long at)
        {
            this.value = value;
            this.at = at;
        }
    }

    private BiliApi() {}

    public static BiliData.VideoInfo view(TvChannel channel) throws BiliData.BiliException
    {
        String key = channel.key();
        long now = System.currentTimeMillis();
        Cached<BiliData.VideoInfo> c = VIEWS.get(key);
        if(c != null && now - c.at < VIEW_TTL)
            return c.value;
        ensureBuvid();
        String query = channel.getBvid() != null ? "bvid=" + channel.getBvid() : "aid=" + channel.getAid();
        JsonObject root = json(URI.create(TvDev.apiBase() + "/x/web-interface/view?" + query));
        BiliData.VideoInfo info = BiliData.parseView(root, channel.getPage());
        if(VIEWS.size() > 64)
            VIEWS.clear();
        VIEWS.put(key, new Cached<>(info, now));
        return info;
    }

    /** 取一次 playurl：html5 = 单文件 MP4（platform=html5），否则 DASH */
    interface PlayurlFetcher
    {
        @Nullable
        BiliData.Stream fetch(boolean html5) throws BiliData.BiliException;
    }

    /**
     * 取流：先试 html5 的单文件 MP4，不行再取 DASH。清晰度不超过 maxQn（取不到时接口会给更低的）。
     * 有 cookie 且 html5 给的清晰度低于 maxQn 时（html5 常把 720P 以上锁在 480P）也试 DASH，两者取清晰度高的。
     */
    public static BiliData.Stream playurl(BiliData.VideoInfo info, int maxQn) throws BiliData.BiliException
    {
        ensureBuvid();
        boolean cookie = TvClientConfig.get().hasCookie();
        return selectStream(html5 -> BiliData.parsePlayurl(json(playurlUri(playurlParams(info, maxQn, html5, cookie))), maxQn), maxQn, cookie);
    }

    /** 选流的逻辑（和网络分开，便于测试）；异常信息里不带 cookie */
    static BiliData.Stream selectStream(PlayurlFetcher fetcher, int maxQn, boolean cookie) throws BiliData.BiliException
    {
        BiliData.BiliException last = null;
        BiliData.Stream html5 = null;
        try
        {
            html5 = fetcher.fetch(true);
        }
        catch(BiliData.BiliException e)
        {
            last = e;
        }
        if(html5 != null && (!cookie || html5.quality >= maxQn))
            return html5;
        BiliData.Stream dash = null;
        try
        {
            dash = fetcher.fetch(false);
        }
        catch(BiliData.BiliException e)
        {
            last = e;
        }
        if(dash != null && (html5 == null || dash.quality > html5.quality))
            return dash;
        if(html5 != null)
            return html5;
        if(last != null)
            throw last;
        throw new BiliData.BiliException("cfm.tv.bili.no_stream", "no durl or dash");
    }

    private static Map<String, String> playurlParams(BiliData.VideoInfo info, int maxQn, boolean html5, boolean cookie)
    {
        Map<String, String> params = new LinkedHashMap<>();
        if(!info.bvid.isEmpty())
            params.put("bvid", info.bvid);
        else
            params.put("avid", Long.toString(info.aid));
        params.put("cid", Long.toString(info.cid));
        params.put("qn", Integer.toString(maxQn));
        params.put("fnver", "0");
        params.put("fourk", "0");
        if(html5)
        {
            params.put("fnval", "1");
            params.put("platform", "html5");
            if(maxQn >= BiliData.QN_720P)
                params.put("high_quality", "1");
        }
        else
        {
            params.put("fnval", "16");
        }
        if(!cookie)
            params.put("gaia_source", "view-card");
        return params;
    }

    private static URI playurlUri(Map<String, String> params)
    {
        String key = mixinKey();
        if(key == null)
        {
            // 取不到 WBI 口令时退回不签名的旧接口
            StringBuilder q = new StringBuilder();
            for(Map.Entry<String, String> e : params.entrySet())
            {
                if(q.length() > 0)
                    q.append('&');
                q.append(WbiSigner.encode(e.getKey())).append('=').append(WbiSigner.encode(e.getValue()));
            }
            return URI.create(TvDev.apiBase() + "/x/player/playurl?" + q);
        }
        return URI.create(TvDev.apiBase() + "/x/player/wbi/playurl?" + WbiSigner.sign(params, System.currentTimeMillis() / 1000, key));
    }

    // ================= 直播（服主 11-18） =================

    /** 直播间信息：短号换真实房间号、开播状态（room_init，不用签名；状态要新的，不缓存） */
    public static LiveData.Room liveRoom(long id) throws BiliData.BiliException
    {
        ensureBuvid();
        return LiveData.parseRoomInit(json(URI.create(TvDev.liveApiBase() + "/room/v1/Room/room_init?id=" + id)));
    }

    /** getRoomPlayInfo 的参数（同 PiliPlus：protocol 0,1 = FLV 与 HLS，format 0,1 = flv 与 ts，codec 0,1 = H.264 与 H.265） */
    private static Map<String, String> livePlayParams(long roomId, int qn)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("room_id", Long.toString(roomId));
        params.put("protocol", "0,1");
        params.put("format", "0,1");
        params.put("codec", "0,1");
        params.put("qn", Integer.toString(qn));
        params.put("platform", "web");
        params.put("ptype", "8");
        params.put("dolby", "5");
        params.put("panorama", "1");
        params.put("web_location", "444.8");
        return params;
    }

    /** 带 WBI 签名的地址；取不到口令时：allowPlain 为真就不签名（getRoomPlayInfo 不签名也能用，11-18 实测），否则报错 */
    private static URI signed(String base, Map<String, String> params, boolean allowPlain) throws BiliData.BiliException
    {
        String key = mixinKey();
        if(key != null)
            return URI.create(base + "?" + WbiSigner.sign(params, System.currentTimeMillis() / 1000, key));
        if(!allowPlain)
            throw new BiliData.BiliException("cfm.tv.bili.network", "no WBI keys");
        StringBuilder q = new StringBuilder();
        for(Map.Entry<String, String> e : params.entrySet())
        {
            if(q.length() > 0)
                q.append('&');
            q.append(WbiSigner.encode(e.getKey())).append('=').append(WbiSigner.encode(e.getValue()));
        }
        return URI.create(base + "?" + q);
    }

    public static LiveData.PlayInfo livePlayInfo(long roomId, int qn) throws BiliData.BiliException
    {
        ensureBuvid();
        return LiveData.parsePlayInfo(json(signed(TvDev.liveApiBase() + "/xlive/web-room/v2/index/getRoomPlayInfo", livePlayParams(roomId, qn), true)));
    }

    /** 直播取流的结果：选中的一路；没开播时 stream 为 null */
    public static final class LiveSource
    {
        public final long roomId;
        @Nullable
        public final LiveData.Stream stream;

        LiveSource(long roomId, @Nullable LiveData.Stream stream)
        {
            this.roomId = roomId;
            this.stream = stream;
        }
    }

    /**
     * 取直播流：按清晰度上限对应的直播清晰度请求（{@link LiveData#wantedQn}），选 FLV 或 HLS-TS（{@link LiveData#select}）；
     * 给的那一档和能给的里最合适的一档（{@link LiveData#pickQn}）不一样时按那一档再请求一次。没开播返回 stream 为 null 的结果。
     */
    public static LiveSource liveStream(long roomId, int vodCap) throws BiliData.BiliException
    {
        int wanted = LiveData.wantedQn(vodCap);
        LiveData.PlayInfo info = livePlayInfo(roomId, wanted);
        long room = info.roomId > 0 ? info.roomId : roomId;
        if(!info.isLive())
            return new LiveSource(room, null);
        LiveData.Stream s = LiveData.select(info.streams);
        if(s == null)
            throw new BiliData.BiliException("cfm.tv.bili.no_stream", "no FLV or HLS-TS stream");
        int pick = LiveData.pickQn(s.acceptQn, wanted);
        if(pick != s.qn && s.acceptQn.contains(pick))
        {
            LiveData.PlayInfo again = livePlayInfo(room, pick);
            LiveData.Stream s2 = again.isLive() ? LiveData.select(again.streams) : null;
            if(s2 != null)
                s = s2;
        }
        return new LiveSource(room, s);
    }

    /** 直播弹幕的令牌与服务器（getDanmuInfo，要 WBI 签名，不签名返回 -352） */
    public static LiveData.DanmuInfo liveDanmuInfo(long roomId) throws BiliData.BiliException
    {
        ensureBuvid();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", Long.toString(roomId));
        params.put("type", "0");
        params.put("web_location", "444.8");
        return LiveData.parseDanmuInfo(json(signed(TvDev.liveApiBase() + "/xlive/web-room/v1/index/getDanmuInfo", params, false)));
    }

    // ================= 搜索（服主 11-18） =================

    /**
     * 分类搜索一页（视频或直播间）。参数同 PiliPlus：page_size 20、platform pc、web_location 1430654，WBI 签名
     * （取不到口令时用不签名的旧接口）。没有 buvid3 时接口只回风控（v_voucher，11-18 实测），所以先取匿名 buvid；
     * 风控了而玩家没配 cookie 时换一个匿名 buvid 再试一次，还不行就报“被风控”（已经搜出来的结果由界面留着）。
     */
    public static BiliSearch.Page search(BiliSearch.Type type, String keyword, int page) throws BiliData.BiliException
    {
        for(int attempt = 0; ; attempt++)
        {
            ensureBuvid();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("search_type", type.apiName);
            params.put("keyword", keyword);
            params.put("page", Integer.toString(page));
            params.put("page_size", Integer.toString(BiliSearch.PAGE_SIZE));
            params.put("platform", "pc");
            params.put("web_location", "1430654");
            URI uri = mixinKey() != null ? signed(TvDev.apiBase() + "/x/web-interface/wbi/search/type", params, false) : signed(TvDev.apiBase() + "/x/web-interface/search/type", params, true);
            JsonObject root = json(uri);
            if(BiliSearch.isChallenge(root) && attempt == 0 && !TvClientConfig.get().hasCookie())
            {
                // 换一个匿名 buvid 再试一次
                spiTried = false;
                TvCookies.setBuvid("", "");
                continue;
            }
            return BiliSearch.parse(root, type, page);
        }
    }

    /** 弹幕（取不到返回空表，不算错误） */
    public static List<Danmaku.Comment> danmaku(long cid)
    {
        try
        {
            String xml;
            try
            {
                xml = TvHttp.getText(URI.create(TvDev.commentBase() + "/" + cid + ".xml"), HostPolicy.Purpose.API, MAX_DANMAKU);
            }
            catch(IOException e)
            {
                xml = TvHttp.getText(URI.create(TvDev.apiBase() + "/x/v1/dm/list.so?oid=" + cid), HostPolicy.Purpose.API, MAX_DANMAKU);
            }
            return Danmaku.parse(xml);
        }
        catch(IOException | RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().info("TV video: no danmaku for cid {}: {}", cid, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** 封面图（缩到宽不超过 maxWidth）；取不到返回 null */
    public static BufferedImage cover(String url, int maxWidth)
    {
        URI uri = HostPolicy.upgrade(url);
        if(uri == null)
            return null;
        try(TvHttp.Response res = TvHttp.get(uri, HostPolicy.Purpose.COVER, "image/*", -1, -1, MAX_COVER, 15_000))
        {
            if(res.status != 200)
                return null;
            byte[] data;
            try(InputStream in = res.body())
            {
                data = TvHttp.readAll(in, MAX_COVER);
            }
            BufferedImage image = decodeImage(data, 4096);
            if(image == null)
                return null;
            if(image.getWidth() <= maxWidth)
                return image;
            int h = Math.max(1, image.getHeight() * maxWidth / image.getWidth());
            BufferedImage scaled = new BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, maxWidth, h, null);
            g.dispose();
            return scaled;
        }
        catch(IOException | RuntimeException e)
        {
            return null;
        }
    }

    /** 先读宽高，太大的不解码（防止解压炸弹） */
    static BufferedImage decodeImage(byte[] data, int maxSide) throws IOException
    {
        try(ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data)))
        {
            if(in == null)
                return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if(!readers.hasNext())
                return null;
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, true, true);
                if(reader.getWidth(0) > maxSide || reader.getHeight(0) > maxSide)
                    return null;
                return reader.read(0);
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    private static String mixinKey()
    {
        long now = System.currentTimeMillis();
        if(mixinKey != null && now - mixinKeyAt < NAV_TTL)
            return mixinKey;
        try
        {
            String[] keys = BiliData.parseNavKeys(json(URI.create(TvDev.apiBase() + "/x/web-interface/nav"), true));
            if(keys != null)
            {
                mixinKey = WbiSigner.mixinKey(keys[0], keys[1]);
                mixinKeyAt = now;
            }
        }
        catch(BiliData.BiliException e)
        {
            MrCrayfishFurnitureMod.logger().info("TV video: WBI keys unavailable: {}", e.getMessage());
        }
        return mixinKey;
    }

    /** 没有配 cookie（或 cookie 里没有 buvid3）时取一次匿名 buvid */
    private static void ensureBuvid()
    {
        if(spiTried || TvCookies.contains(TvCookies.sanitize(TvClientConfig.get().cookie), "buvid3"))
            return;
        spiTried = true;
        try
        {
            String[] b = BiliData.parseSpi(json(URI.create(TvDev.apiBase() + "/x/frontend/finger/spi")));
            if(b != null)
                TvCookies.setBuvid(b[0], b[1]);
        }
        catch(BiliData.BiliException e)
        {
            MrCrayfishFurnitureMod.logger().info("TV video: anonymous buvid unavailable: {}", e.getMessage());
        }
    }

    private static JsonObject json(URI uri) throws BiliData.BiliException
    {
        return json(uri, false);
    }

    /** anyCode：nav 未登录时 code 是 -101 但照样有用，不在这里判断 code */
    private static JsonObject json(URI uri, boolean anyCode) throws BiliData.BiliException
    {
        String text;
        try
        {
            text = TvHttp.getText(uri, HostPolicy.Purpose.API, MAX_JSON);
        }
        catch(TvHttp.HttpStatusException e)
        {
            throw new BiliData.BiliException(e.status == 412 ? "cfm.tv.bili.blocked" : "cfm.tv.bili.network", e.getMessage());
        }
        catch(TvProxy.AuthException e)
        {
            throw new BiliData.BiliException("cfm.tv.bili.proxy_auth", e.getMessage());
        }
        catch(TvProxy.ProxyException e)
        {
            throw new BiliData.BiliException("cfm.tv.bili.proxy", e.getMessage());
        }
        catch(IOException e)
        {
            throw new BiliData.BiliException("cfm.tv.bili.network", e.getMessage());
        }
        try
        {
            JsonElement e = new JsonParser().parse(text);
            if(!e.isJsonObject())
                throw new BiliData.BiliException("cfm.tv.bili.error", "not an object");
            return e.getAsJsonObject();
        }
        catch(RuntimeException e)
        {
            throw new BiliData.BiliException("cfm.tv.bili.error", "bad JSON");
        }
    }
}
