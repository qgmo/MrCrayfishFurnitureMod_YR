package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Locale;

/**
 * 电视取视频时允许访问的网址（纯逻辑，悠然服务器魔改）。模组自己的 HTTP 请求一律先过这里：
 * 只用 https、只访问白名单域名，按用途分开（接口、封面、视频 CDN、本地库下载）。
 * 开发测试时（系统属性 {@code cfm.tv.dev}）另外放行本机 127.0.0.1 的 http。
 */
public final class HostPolicy
{
    public enum Purpose
    {
        /** bilibili 接口（视频信息、取流地址、WBI 口令、匿名 buvid、弹幕、搜索；直播间信息、直播取流地址、直播弹幕的令牌） */
        API,
        /** 封面图 */
        COVER,
        /** 视频、音频字节（bilibili 视频 CDN；直播的 FLV 流、HLS 列表与分片也在同一批域名下） */
        CDN,
        /** 下载 FFmpeg 本地库 */
        LIBRARY
    }

    private HostPolicy() {}

    /** 这个网址能不能用于 purpose；devLoopback 为 true 时放行 http://127.0.0.1 */
    public static boolean allowed(@Nullable URI uri, Purpose purpose, boolean devLoopback)
    {
        if(uri == null || uri.getHost() == null || uri.getRawUserInfo() != null)
            return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if(devLoopback && scheme.equals("http") && host.equals("127.0.0.1"))
            return true;
        if(!scheme.equals("https"))
            return false;
        switch(purpose)
        {
            case API:
                return host.equals("api.bilibili.com") || host.equals("comment.bilibili.com") || host.equals("api.live.bilibili.com");
            case COVER:
                // 搜索结果里的视频封面有的在 archive.biliimg.com（11-18 实测）
                return (host.endsWith(".hdslb.com") || host.endsWith(".biliimg.com")) && isPlainHost(host);
            case CDN:
                return isCdnHost(host);
            case LIBRARY:
                return host.equals("dl-yr.qg.mo.cn") || host.equals("maven.aliyun.com") || host.equals("repo1.maven.org");
            default:
                return false;
        }
    }

    /**
     * 直播弹幕服务器：{@code wss://*.chat.bilibili.com:443/sub}（getDanmuInfo 给的 host_list，11-18 实测是
     * {@code broadcastlv-mixed.chat.bilibili.com}、{@code hw-sg-live-comet-01.chat.bilibili.com} 一类）。端口只认 443。
     */
    public static boolean allowedLiveChat(@Nullable URI uri)
    {
        if(uri == null || uri.getHost() == null || uri.getRawUserInfo() != null || !"wss".equalsIgnoreCase(uri.getScheme()))
            return false;
        if(uri.getPort() != 443 && uri.getPort() != -1)
            return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(".chat.bilibili.com") && isPlainHost(host);
    }

    /** bilibili 视频 CDN：*.bilivideo.com、*.bilivideo.cn（含 mcdn），海外的 upos-*-mirrorakam.akamaized.net */
    static boolean isCdnHost(String host)
    {
        if(!isPlainHost(host))
            return false;
        if(host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn"))
            return true;
        return host.startsWith("upos-") && host.endsWith("-mirrorakam.akamaized.net") && host.indexOf('.') == host.length() - ".akamaized.net".length();
    }

    /** 只由字母、数字、点、连字符组成（排除 IP 地址以外的奇怪写法；数字 IP 也不收） */
    private static boolean isPlainHost(String host)
    {
        if(host.isEmpty() || host.startsWith(".") || host.contains(".."))
            return false;
        boolean letter = false;
        for(int i = 0; i < host.length(); i++)
        {
            char c = host.charAt(i);
            if(c >= 'a' && c <= 'z')
                letter = true;
            else if(!(c >= '0' && c <= '9') && c != '.' && c != '-')
                return false;
        }
        return letter;
    }

    /**
     * 接口返回的流地址偶尔是 http，统一改成 https（CDN 都支持）；改不了返回 null。
     */
    @Nullable
    public static URI upgrade(@Nullable String url)
    {
        if(url == null)
            return null;
        try
        {
            String text = url.trim();
            if(text.startsWith("//"))
            {
                // 搜索结果的封面地址不带协议
                text = "https:" + text;
            }
            URI uri = new URI(text);
            if("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().equals("127.0.0.1"))
            {
                uri = new URI("https" + text.substring(4));
            }
            return uri;
        }
        catch(Exception e)
        {
            return null;
        }
    }
}
