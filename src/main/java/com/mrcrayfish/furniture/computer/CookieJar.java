package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一台电脑的 Cookie（悠然服务器魔改，服主 11-15：Cookie 存在服务端、跟着电脑走，同一台电脑上 A 登录后 B 来用看到的是 A 的登录状态）。
 * 按 RFC 6265（及 6265bis 的 SameSite、Secure 来源、名字前缀）处理：
 * <ul>
 * <li>Domain / Path / Expires / Max-Age（Max-Age 优先）/ Secure / HttpOnly / SameSite；没有 Domain 的只给原主机（host-only）；</li>
 * <li>Domain 不能是顶级域（{@code .yr}、{@code .com} 这类，当作公共后缀），按 IP 访问时不收 Domain；</li>
 * <li>Secure 的只能由 https 设、只在 https 上发；http 的响应不能覆盖已有的同名 Secure Cookie；</li>
 * <li>SameSite 不写按 Lax；{@code None} 必须带 Secure；跨站时 Strict 不发，Lax 只在顶层的 GET 跳转时发（表单跨站 POST 不发）；</li>
 * <li>HttpOnly 只是照存（浏览器没有脚本）。</li>
 * </ul>
 * 上限：每台电脑最多 {@value #MAX_COOKIES} 条、合计 {@value #MAX_TOTAL_BYTES} 字节，每个域名最多 {@value #MAX_PER_DOMAIN} 条，
 * 一条的名字加值最多 {@value #MAX_NAME_VALUE_BYTES} 字节；超了先丢过期的，再丢最早的。
 * <p>
 * 纯逻辑，时间（现实时间，毫秒）由调用方给；不是线程安全的（服务端只在主线程上用，客户端只在主线程上用）。
 */
public final class CookieJar
{
    public static final int MAX_COOKIES = 50;
    public static final int MAX_PER_DOMAIN = 20;
    public static final int MAX_TOTAL_BYTES = 8192;
    public static final int MAX_NAME_VALUE_BYTES = 4096;
    public static final int MAX_ATTRIBUTE_BYTES = 1024;
    /** 有效期最长 400 天（6265bis） */
    public static final long MAX_AGE_MS = 400L * 24 * 3600 * 1000;
    /** 编码格式版本 */
    private static final int FORMAT = 1;

    public enum SameSite
    {
        STRICT, LAX, NONE
    }

    /** 一条 Cookie */
    public static final class Cookie
    {
        public final String name;
        public final String value;
        /** 小写，不带前面的点；IPv6 地址带方括号 */
        public final String domain;
        public final String path;
        public final boolean hostOnly;
        public final boolean secure;
        public final boolean httpOnly;
        public final SameSite sameSite;
        /** 过期时刻（毫秒）；会话 Cookie（没写 Expires / Max-Age）为 {@link Long#MAX_VALUE} */
        public final long expiry;
        /** 创建次序（毫秒 × 1000 + 序号，只用来排先后） */
        public final long creation;

        Cookie(String name, String value, String domain, String path, boolean hostOnly, boolean secure, boolean httpOnly, SameSite sameSite, long expiry,
               long creation)
        {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.hostOnly = hostOnly;
            this.secure = secure;
            this.httpOnly = httpOnly;
            this.sameSite = sameSite;
            this.expiry = expiry;
            this.creation = creation;
        }

        public boolean persistent()
        {
            return expiry != Long.MAX_VALUE;
        }

        int size()
        {
            return utf8(name) + utf8(value) + utf8(domain) + utf8(path);
        }

        @Override
        public String toString()
        {
            return name + "=" + value + " (" + (hostOnly ? "" : ".") + domain + path + (secure ? " secure" : "") + " " + sameSite + ")";
        }
    }

    /** 一次请求的来历（决定 SameSite 的 Cookie 发不发） */
    public static final class Context
    {
        /** 顶层导航（地址栏、点链接、提交表单、跳转）；图片等是 false */
        public final boolean topLevel;
        /** 方法是不是 GET / HEAD */
        public final boolean safeMethod;
        /** 跨站：发起这次请求的页面（含跳转链上每一站）和目标不是同一个站 */
        public final boolean crossSite;

        public Context(boolean topLevel, boolean safeMethod, boolean crossSite)
        {
            this.topLevel = topLevel;
            this.safeMethod = safeMethod;
            this.crossSite = crossSite;
        }

        /** 地址栏、刷新、前进后退：用户自己发起的，算同站 */
        public static final Context USER = new Context(true, true, false);
    }

    private final List<Cookie> cookies = new ArrayList<>();
    private long sequence;

    public List<Cookie> all()
    {
        return Collections.unmodifiableList(cookies);
    }

    public int size()
    {
        return cookies.size();
    }

    public boolean isEmpty()
    {
        return cookies.isEmpty();
    }

    public void clear()
    {
        cookies.clear();
    }

    /** 有 Cookie 的站点数（给“设置”里显示） */
    public int siteCount()
    {
        Set<String> s = new HashSet<>();
        for(Cookie c : cookies)
            s.add(site(c.domain));
        return s.size();
    }

    /** 去掉过期的；返回去掉了几条 */
    public int purge(long now)
    {
        int n = 0;
        Iterator<Cookie> it = cookies.iterator();
        while(it.hasNext())
        {
            if(it.next().expiry <= now)
            {
                it.remove();
                n++;
            }
        }
        return n;
    }

    // ---- 收（Set-Cookie，RFC 6265 第 5.2、5.3 节） ----

    /**
     * 按响应里的一条 {@code Set-Cookie} 存（或删）。{@code url} 是这次请求的网址（规范化过的 http / https 网址）。
     *
     * @return 收下（含删除）返回 true，不合格忽略返回 false
     */
    public boolean set(String setCookie, String url, long now)
    {
        String host = UrlInput.hostOf(url);
        if(host == null || setCookie == null)
            return false;
        boolean secureOrigin = UrlInput.isSecure(url);
        String requestPath = pathOf(url);
        // 名字、值
        int semi = setCookie.indexOf(';');
        String pair = semi < 0 ? setCookie : setCookie.substring(0, semi);
        if(hasControl(pair))
            return false;
        int eq = pair.indexOf('=');
        String name = eq < 0 ? "" : pair.substring(0, eq).trim();
        String value = (eq < 0 ? pair : pair.substring(eq + 1)).trim();
        if(name.isEmpty() && value.isEmpty())
            return false;
        if(utf8(name) + utf8(value) > MAX_NAME_VALUE_BYTES)
            return false;
        // 属性
        Long maxAge = null;
        Long expires = null;
        String domainAttr = null;
        String pathAttr = null;
        boolean secure = false;
        boolean httpOnly = false;
        SameSite sameSite = null;
        if(semi >= 0)
        {
            for(String av : setCookie.substring(semi + 1).split(";"))
            {
                int e = av.indexOf('=');
                String an = (e < 0 ? av : av.substring(0, e)).trim().toLowerCase(Locale.ROOT);
                String val = e < 0 ? "" : av.substring(e + 1).trim();
                if(utf8(val) > MAX_ATTRIBUTE_BYTES)
                    continue;
                switch(an)
                {
                    case "expires":
                    {
                        Long t = CookieDate.parse(val);
                        if(t != null)
                            expires = t;
                        break;
                    }
                    case "max-age":
                    {
                        if(val.matches("-?[0-9]+"))
                        {
                            long sec;
                            try
                            {
                                sec = Long.parseLong(val);
                            }
                            catch(NumberFormatException x)
                            {
                                sec = val.startsWith("-") ? Long.MIN_VALUE / 1000 : Long.MAX_VALUE / 1000;
                            }
                            maxAge = sec;
                        }
                        break;
                    }
                    case "domain":
                    {
                        if(!val.isEmpty())
                        {
                            String d = val.startsWith(".") ? val.substring(1) : val;
                            domainAttr = d.toLowerCase(Locale.ROOT);
                        }
                        break;
                    }
                    case "path":
                        pathAttr = val.startsWith("/") ? val : null;
                        break;
                    case "secure":
                        secure = true;
                        break;
                    case "httponly":
                        httpOnly = true;
                        break;
                    case "samesite":
                    {
                        String v = val.toLowerCase(Locale.ROOT);
                        sameSite = v.equals("strict") ? SameSite.STRICT : v.equals("lax") ? SameSite.LAX : v.equals("none") ? SameSite.NONE : SameSite.LAX;
                        break;
                    }
                    default:
                        break;
                }
            }
        }
        // 过期时刻：Max-Age 优先；最长 400 天
        long expiry;
        if(maxAge != null)
            expiry = maxAge <= 0 ? Long.MIN_VALUE : now + Math.min(MAX_AGE_MS, maxAge * 1000L > 0 ? maxAge * 1000L : MAX_AGE_MS);
        else if(expires != null)
            expiry = Math.min(expires, now + MAX_AGE_MS);
        else
            expiry = Long.MAX_VALUE;
        // 域名
        String domain;
        boolean hostOnly;
        if(domainAttr == null)
        {
            domain = host;
            hostOnly = true;
        }
        else
        {
            if(isIp(host))
            {
                // 按 IP 访问：Domain 只能是这个地址本身，等于没写
                if(!domainAttr.equals(host) && !("[" + domainAttr + "]").equals(host))
                    return false;
                domain = host;
                hostOnly = true;
            }
            else
            {
                if(!domainAttr.contains(".") || domainAttr.endsWith(".") || !domainMatch(host, domainAttr))
                    // 顶级域（公共后缀）或者不是这个主机的上级域
                    return false;
                domain = domainAttr;
                hostOnly = false;
            }
        }
        String path = pathAttr != null ? pathAttr : defaultPath(requestPath);
        // Secure：只能由 https 设
        if(secure && !secureOrigin)
            return false;
        if(sameSite == null)
            sameSite = SameSite.LAX;
        if(sameSite == SameSite.NONE && !secure)
            return false;
        // 名字前缀（6265bis 第 4.1.3 节）
        if(name.regionMatches(true, 0, "__Secure-", 0, 9) && !secure)
            return false;
        if(name.regionMatches(true, 0, "__Host-", 0, 7) && (!secure || !hostOnly || !path.equals("/")))
            return false;
        // http 的响应不能覆盖（或删除）已有的同名 Secure Cookie
        if(!secureOrigin)
        {
            for(Cookie c : cookies)
            {
                if(c.secure && c.name.equals(name) && (domainMatch(c.domain, domain) || domainMatch(domain, c.domain)) && pathMatch(path, c.path))
                    return false;
            }
        }
        // 换掉同名、同域、同路径的旧条目（保留它的创建次序）
        long creation = -1;
        Iterator<Cookie> it = cookies.iterator();
        while(it.hasNext())
        {
            Cookie c = it.next();
            if(c.name.equals(name) && c.domain.equals(domain) && c.path.equals(path))
            {
                creation = c.creation;
                it.remove();
            }
        }
        if(expiry <= now)
            return true;
        if(creation < 0)
            creation = now * 1000 + (sequence++ % 1000);
        Cookie c = new Cookie(name, value, domain, path, hostOnly, secure, httpOnly, sameSite, expiry, creation);
        cookies.add(c);
        enforceLimits(now, c);
        return true;
    }

    /** 超了上限：先丢过期的，再丢同域名里最早的，再丢整台电脑最早的（刚收的那条最后才丢） */
    private void enforceLimits(long now, Cookie fresh)
    {
        purge(now);
        List<Cookie> sameDomain = new ArrayList<>();
        for(Cookie c : cookies)
        {
            if(c.domain.equals(fresh.domain))
                sameDomain.add(c);
        }
        sameDomain.sort(Comparator.comparingLong(c -> c == fresh ? Long.MAX_VALUE : c.creation));
        for(int i = 0; i < sameDomain.size() - MAX_PER_DOMAIN; i++)
            cookies.remove(sameDomain.get(i));
        while(cookies.size() > MAX_COOKIES || totalBytes() > MAX_TOTAL_BYTES)
        {
            Cookie oldest = null;
            for(Cookie c : cookies)
            {
                if(c != fresh && (oldest == null || c.creation < oldest.creation))
                    oldest = c;
            }
            if(oldest == null)
            {
                cookies.remove(fresh);
                break;
            }
            cookies.remove(oldest);
        }
    }

    int totalBytes()
    {
        int n = 0;
        for(Cookie c : cookies)
            n += c.size();
        return n;
    }

    // ---- 发（Cookie 首部，RFC 6265 第 5.4 节） ----

    /** 请求 {@code url} 时要带的 {@code Cookie} 首部；没有为 null */
    @Nullable
    public String header(String url, Context ctx, long now)
    {
        String host = UrlInput.hostOf(url);
        if(host == null)
            return null;
        boolean secure = UrlInput.isSecure(url);
        String path = pathOf(url);
        List<Cookie> match = new ArrayList<>();
        for(Cookie c : cookies)
        {
            if(c.expiry <= now)
                continue;
            if(c.hostOnly ? !c.domain.equals(host) : !domainMatch(host, c.domain))
                continue;
            if(!pathMatch(path, c.path))
                continue;
            if(c.secure && !secure)
                continue;
            if(ctx.crossSite)
            {
                if(c.sameSite == SameSite.STRICT)
                    continue;
                if(c.sameSite == SameSite.LAX && !(ctx.topLevel && ctx.safeMethod))
                    continue;
            }
            match.add(c);
        }
        if(match.isEmpty())
            return null;
        // 路径长的在前，同样长的先创建的在前
        match.sort((a, b) -> a.path.length() != b.path.length() ? b.path.length() - a.path.length() : Long.compare(a.creation, b.creation));
        StringBuilder sb = new StringBuilder();
        for(Cookie c : match)
        {
            if(sb.length() > 0)
                sb.append("; ");
            if(!c.name.isEmpty())
                sb.append(c.name).append('=');
            sb.append(c.value);
        }
        return sb.toString();
    }

    // ---- 站点、域名、路径 ----

    /** “站”：域名的最后两级（{@code portal.yr}、{@code captcha.yr} 是两个站）；IP 地址就是它自己 */
    public static String site(String host)
    {
        if(host == null)
            return "";
        if(isIp(host))
            return host;
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        int last = h.lastIndexOf('.');
        if(last < 0)
            return h;
        int prev = h.lastIndexOf('.', last - 1);
        return prev < 0 ? h : h.substring(prev + 1);
    }

    /** 两个网址是不是同一个站（不管协议、端口） */
    public static boolean sameSite(@Nullable String urlA, @Nullable String urlB)
    {
        String a = UrlInput.hostOf(urlA);
        String b = UrlInput.hostOf(urlB);
        return a != null && b != null && site(a).equals(site(b));
    }

    static boolean isIp(String host)
    {
        return host.startsWith("[") || host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    /** RFC 6265 第 5.1.3 节 */
    static boolean domainMatch(String host, String domain)
    {
        if(host.equals(domain))
            return true;
        return !isIp(host) && host.endsWith("." + domain);
    }

    /** RFC 6265 第 5.1.4 节 */
    static boolean pathMatch(String requestPath, String cookiePath)
    {
        if(requestPath.equals(cookiePath))
            return true;
        if(requestPath.startsWith(cookiePath))
            return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
        return false;
    }

    /** 默认路径：请求路径最后一个 / 之前的部分（只有一个 / 时是 /） */
    static String defaultPath(String requestPath)
    {
        if(requestPath.isEmpty() || requestPath.charAt(0) != '/')
            return "/";
        int last = requestPath.lastIndexOf('/');
        return last <= 0 ? "/" : requestPath.substring(0, last);
    }

    /** 网址的路径（不含查询、片段）；没有为 / */
    static String pathOf(String url)
    {
        int scheme = url.indexOf("://");
        int start = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        if(start < 0)
            return "/";
        int end = url.length();
        for(int i = start; i < url.length(); i++)
        {
            char c = url.charAt(i);
            if(c == '?' || c == '#')
            {
                end = i;
                break;
            }
        }
        String p = url.substring(start, end);
        return p.isEmpty() ? "/" : p;
    }

    private static boolean hasControl(String s)
    {
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if((c < 0x20 && c != '\t') || c == 0x7F)
                return true;
        }
        return false;
    }

    private static int utf8(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- 编码（服务端存档、发给操作者） ----

    /**
     * 编码。{@code absolute} 为 true 时存过期时刻本身（服务端存档）；为 false 时存“还剩多久”（发给客户端，客户端按自己的时钟算，
     * 两边时钟不一样也不要紧）。
     */
    public byte[] encode(boolean absolute, long now)
    {
        try
        {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeByte(FORMAT);
            List<Cookie> live = new ArrayList<>();
            for(Cookie c : cookies)
            {
                if(c.expiry > now)
                    live.add(c);
            }
            out.writeShort(live.size());
            for(Cookie c : live)
            {
                out.writeUTF(c.name);
                out.writeUTF(c.value);
                out.writeUTF(c.domain);
                out.writeUTF(c.path);
                out.writeByte((c.hostOnly ? 1 : 0) | (c.secure ? 2 : 0) | (c.httpOnly ? 4 : 0));
                out.writeByte(c.sameSite.ordinal());
                out.writeLong(!c.persistent() ? -1 : absolute ? c.expiry : c.expiry - now);
                out.writeLong(c.creation);
            }
            out.flush();
            return b.toByteArray();
        }
        catch(IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解码（{@link #encode} 的结果）。格式不对、条数或大小超限时返回 null（读存档时就当没有）。逐条照收时的规则再查一遍
     * （域名、路径、名字长度），不合格的条目丢掉。
     */
    @Nullable
    public static CookieJar decode(byte[] data, boolean absolute, long now)
    {
        if(data == null || data.length > MAX_TOTAL_BYTES * 3 + MAX_COOKIES * 64)
            return null;
        try
        {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if(in.readByte() != FORMAT)
                return null;
            int n = in.readUnsignedShort();
            if(n > MAX_COOKIES)
                return null;
            CookieJar jar = new CookieJar();
            for(int i = 0; i < n; i++)
            {
                String name = in.readUTF();
                String value = in.readUTF();
                String domain = in.readUTF().toLowerCase(Locale.ROOT);
                String path = in.readUTF();
                int flags = in.readUnsignedByte();
                int ss = in.readUnsignedByte();
                long exp = in.readLong();
                long creation = in.readLong();
                if(ss >= SameSite.values().length || domain.isEmpty() || !path.startsWith("/") || hasControl(name) || hasControl(value)
                        || utf8(name) + utf8(value) > MAX_NAME_VALUE_BYTES || (name.isEmpty() && value.isEmpty()))
                    continue;
                long expiry = exp < 0 ? Long.MAX_VALUE : absolute ? exp : now + Math.min(exp, MAX_AGE_MS);
                if(expiry <= now)
                    continue;
                jar.cookies.add(new Cookie(name, value, domain, path, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, SameSite.values()[ss], expiry, creation));
            }
            if(in.available() != 0)
                return null;
            jar.trimToLimits();
            return jar;
        }
        catch(IOException | RuntimeException e)
        {
            return null;
        }
    }

    /** 读进来以后再按上限截一次（最早的先丢） */
    private void trimToLimits()
    {
        cookies.sort(Comparator.comparingLong(c -> c.creation));
        while(cookies.size() > MAX_COOKIES || totalBytes() > MAX_TOTAL_BYTES)
            cookies.remove(0);
    }
}
