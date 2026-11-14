package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.tv.TvChannel;

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

    /**
     * 取流：先试 html5 的单文件 MP4，不行再取 DASH。清晰度不超过 maxQn（取不到时接口会给更低的）。
     */
    public static BiliData.Stream playurl(BiliData.VideoInfo info, int maxQn) throws BiliData.BiliException
    {
        ensureBuvid();
        boolean cookie = TvClientConfig.get().hasCookie();
        BiliData.BiliException last = null;
        for(int attempt = 0; attempt < 2; attempt++)
        {
            boolean html5 = attempt == 0;
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
            try
            {
                BiliData.Stream stream = BiliData.parsePlayurl(json(playurlUri(params)), maxQn);
                if(stream != null)
                    return stream;
            }
            catch(BiliData.BiliException e)
            {
                last = e;
            }
        }
        if(last != null)
            throw last;
        throw new BiliData.BiliException("cfm.tv.bili.no_stream", "no durl or dash");
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
