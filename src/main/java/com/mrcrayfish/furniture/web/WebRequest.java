package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.computer.FormEncoding;
import moe.qingu.youran.api.network.Ipv6Address;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网站收到的一个 HTTP 请求（在网络线程上交给 {@link WebHandler}）。
 * {@link #remoteAddress} 是游戏内网络里发请求的设备地址（家具电脑就是这台电脑那一格的地址，服务器的网卡校验过源地址）。
 * 要知道是谁在用、电脑在哪，走公开接口：{@code id.yr} 一键登录、定位模块的位置证明（和玩家自己搭的网站一样）。
 */
public final class WebRequest
{
    public final String method;
    /** 请求行里的目标（路径 + 查询） */
    public final String target;
    public final String path;
    /** 问号后面的部分；没有为空串 */
    public final String query;
    /** 首部（名字小写；同名的用逗号连起来） */
    public final Map<String, String> headers;
    public final byte[] body;
    public final Ipv6Address remoteAddress;
    public final int remotePort;
    /** 是不是从 https（443）来的 */
    public final boolean secure;

    public WebRequest(String method, String target, Map<String, String> headers, byte[] body, Ipv6Address remoteAddress, int remotePort, boolean secure)
    {
        this.method = method;
        this.target = target;
        int q = target.indexOf('?');
        this.path = q < 0 ? target : target.substring(0, q);
        this.query = q < 0 ? "" : target.substring(q + 1);
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.secure = secure;
    }

    @Nullable
    public String header(String name)
    {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Host 首部（不带端口）；没有为空串 */
    public String host()
    {
        String h = header("host");
        if(h == null)
            return "";
        if(h.startsWith("["))
        {
            int e = h.indexOf(']');
            return e < 0 ? h : h.substring(0, e + 1);
        }
        int c = h.indexOf(':');
        return (c < 0 ? h : h.substring(0, c)).toLowerCase(Locale.ROOT);
    }

    /** 查询串里的参数 */
    public List<Map.Entry<String, String>> queryParameters()
    {
        return FormEncoding.decode(query);
    }

    /** POST 的表单（application/x-www-form-urlencoded）；别的类型返回空表 */
    public List<Map.Entry<String, String>> form()
    {
        String type = header("content-type");
        if(type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded"))
            return Collections.emptyList();
        return FormEncoding.decode(new String(body, StandardCharsets.US_ASCII));
    }

    /** 参数（先看表单，再看查询串）；没有为 null */
    @Nullable
    public String parameter(String name)
    {
        for(Map.Entry<String, String> e : form())
        {
            if(e.getKey().equals(name))
                return e.getValue();
        }
        for(Map.Entry<String, String> e : queryParameters())
        {
            if(e.getKey().equals(name))
                return e.getValue();
        }
        return null;
    }
}
