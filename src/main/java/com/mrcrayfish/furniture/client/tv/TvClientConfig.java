package com.mrcrayfish.furniture.client.tv;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.config.Configuration;

import javax.annotation.Nullable;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;

/**
 * 电视视频的客户端配置（悠然服务器魔改），存在 {@code config/cfm_tv_client.cfg}，只在本机用，不发给服务端。
 * 从遥控器（没对着电视时潜行 + 左键）打开的本地配置界面改（{@link com.mrcrayfish.furniture.gui.GuiTVClientConfig}）。
 * <p>
 * bilibili cookie 只用于发往 api.bilibili.com 的请求，不发给服务端，不写进日志、崩溃报告，界面上也不显示原文。
 */
public final class TvClientConfig
{
    private static final String CATEGORY = "tv";

    /** 能选的清晰度上限：360P、480P、720P、1080P（后两档要有 cookie） */
    public static final int[] QUALITIES = {BiliData.QN_360P, BiliData.QN_480P, BiliData.QN_720P, BiliData.QN_1080P};

    public enum ProxyType
    {
        NONE, HTTP, SOCKS5
    }

    /** 一份配置的值（界面编辑时用一份副本，保存时整份换上） */
    public static final class Values
    {
        public int maxQuality = BiliData.QN_480P;
        public int startRange = 32;
        public int hearRange = 16;
        public int maxDecoders = 2;
        public int masterVolume = 100;
        public boolean alwaysHideDanmaku = false;
        public ProxyType proxyType = ProxyType.NONE;
        public String proxyHost = "";
        public int proxyPort = 0;
        public String cookie = "";

        public Values copy()
        {
            Values v = new Values();
            v.maxQuality = maxQuality;
            v.startRange = startRange;
            v.hearRange = hearRange;
            v.maxDecoders = maxDecoders;
            v.masterVolume = masterVolume;
            v.alwaysHideDanmaku = alwaysHideDanmaku;
            v.proxyType = proxyType;
            v.proxyHost = proxyHost;
            v.proxyPort = proxyPort;
            v.cookie = cookie;
            return v;
        }

        /** 实际用的清晰度上限：没有 cookie 时最高 480P */
        public int effectiveQuality()
        {
            int q = maxQuality;
            if(!hasCookie())
                q = Math.min(q, BiliData.QN_480P);
            return q;
        }

        public boolean hasCookie()
        {
            return !TvCookies.sanitize(cookie).isEmpty();
        }
    }

    private static Configuration config;
    private static volatile Values values = new Values();

    private TvClientConfig() {}

    public static Values get()
    {
        return values;
    }

    public static synchronized void load()
    {
        if(config == null)
        {
            File file = new File(new File(Minecraft.getMinecraft().gameDir, "config"), "cfm_tv_client.cfg");
            config = new Configuration(file);
        }
        config.load();
        Values v = new Values();
        v.maxQuality = nearestQuality(config.getInt("max-quality", CATEGORY, BiliData.QN_480P, BiliData.QN_360P, BiliData.QN_1080P, "Highest bilibili quality code to request: 16 = 360P, 32 = 480P, 64 = 720P, 80 = 1080P. Without a cookie at most 32 (480P) is used."));
        v.startRange = config.getInt("start-range", CATEGORY, 32, 4, 128, "TVs closer than this many blocks start fetching and showing video.");
        v.hearRange = config.getInt("hear-range", CATEGORY, 16, 1, 64, "TVs closer than this many blocks can be heard (volume fades with distance).");
        v.maxDecoders = config.getInt("max-decoders", CATEGORY, 2, 1, 8, "At most this many TVs decode video at the same time; the others show the video cover.");
        v.masterVolume = config.getInt("master-volume", CATEGORY, 100, 0, 100, "TV master volume in percent (multiplied with the TV's own volume and the game's Jukebox/Note Blocks volume).");
        v.alwaysHideDanmaku = config.getBoolean("always-hide-danmaku", CATEGORY, false, "Never show danmaku (bullet comments), even on TVs that have them switched on.");
        v.proxyType = parseProxyType(config.getString("proxy-type", CATEGORY, "none", "Proxy for fetching videos: none, http or socks5.", new String[]{"none", "http", "socks5"}));
        v.proxyHost = config.getString("proxy-host", CATEGORY, "", "Proxy host name or IP address.").trim();
        v.proxyPort = config.getInt("proxy-port", CATEGORY, 0, 0, 65535, "Proxy port.");
        v.cookie = config.getString("bilibili-cookie", CATEGORY, "", "Your own bilibili cookie (optional, e.g. SESSDATA=...). Only sent to api.bilibili.com from this computer, never to the game server. Allows higher qualities.");
        values = v;
        if(config.hasChanged())
            config.save();
    }

    public static synchronized void save(Values v)
    {
        if(config == null)
            load();
        config.get(CATEGORY, "max-quality", BiliData.QN_480P).set(nearestQuality(v.maxQuality));
        config.get(CATEGORY, "start-range", 32).set(clamp(v.startRange, 4, 128));
        config.get(CATEGORY, "hear-range", 16).set(clamp(v.hearRange, 1, 64));
        config.get(CATEGORY, "max-decoders", 2).set(clamp(v.maxDecoders, 1, 8));
        config.get(CATEGORY, "master-volume", 100).set(clamp(v.masterVolume, 0, 100));
        config.get(CATEGORY, "always-hide-danmaku", false).set(v.alwaysHideDanmaku);
        config.get(CATEGORY, "proxy-type", "none").set(v.proxyType.name().toLowerCase(Locale.ROOT));
        config.get(CATEGORY, "proxy-host", "").set(v.proxyHost.trim());
        config.get(CATEGORY, "proxy-port", 0).set(clamp(v.proxyPort, 0, 65535));
        config.get(CATEGORY, "bilibili-cookie", "").set(v.cookie);
        config.save();
        Values copy = v.copy();
        copy.maxQuality = nearestQuality(copy.maxQuality);
        values = copy;
        TvHttp.onConfigChanged();
    }

    public static int nearestQuality(int q)
    {
        int best = QUALITIES[0];
        for(int option : QUALITIES)
        {
            if(option <= q)
                best = option;
        }
        return best;
    }

    private static int clamp(int v, int min, int max)
    {
        return Math.max(min, Math.min(max, v));
    }

    static ProxyType parseProxyType(String s)
    {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if(t.equals("http"))
            return ProxyType.HTTP;
        if(t.equals("socks5") || t.equals("socks"))
            return ProxyType.SOCKS5;
        return ProxyType.NONE;
    }

    /** 配置里的代理；没配或配得不完整时直连（返回 null） */
    @Nullable
    public static Proxy proxy(Values v)
    {
        if(v.proxyType == ProxyType.NONE || v.proxyHost.isEmpty() || v.proxyPort <= 0)
            return null;
        // 让代理那头解析域名
        InetSocketAddress address = InetSocketAddress.createUnresolved(v.proxyHost, v.proxyPort);
        return new Proxy(v.proxyType == ProxyType.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS, address);
    }
}
