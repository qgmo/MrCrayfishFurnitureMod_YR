package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.Ipv6Address;

import javax.annotation.Nullable;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浏览器地址栏与页面里的网址（纯逻辑）：
 * <ul>
 * <li>只写域名或路径（{@code minebay.yr}、{@code test.yr/abc}）时补 {@code http://}；</li>
 * <li>支持 IPv6 地址：{@code http://[fd00::2]:8080/}，也可以只写 {@code [fd00::2]} 或 {@code fd00::2}；</li>
 * <li>只开 {@code http://}、{@code https://}，以及内置页 {@value #HOME}；{@code file:}、{@code ftp:}、{@code javascript:} 等给出明确提示；</li>
 * <li>长度不超过 {@value #MAX_LENGTH}；路径、查询里的空格和非 ASCII 字符按 UTF-8 写成 {@code %XX}，域名转成 IDNA（punycode），去掉 {@code #片段}。</li>
 * </ul>
 */
public final class UrlInput
{
    public static final int MAX_LENGTH = 2048;
    public static final String HOME = "about:home";

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", Pattern.DOTALL);
    private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

    public enum Error
    {
        /** 空的 */
        EMPTY,
        /** 太长 */
        TOO_LONG,
        /** 不支持的协议（detail 是协议名） */
        UNSUPPORTED_SCHEME,
        /** 格式不对 */
        MALFORMED
    }

    public static final class Result
    {
        /** 规范化后的网址；出错时为 null */
        @Nullable
        public final String url;
        @Nullable
        public final Error error;
        /** 出错时的补充（如协议名） */
        @Nullable
        public final String detail;

        private Result(@Nullable String url, @Nullable Error error, @Nullable String detail)
        {
            this.url = url;
            this.error = error;
            this.detail = detail;
        }

        static Result ok(String url)
        {
            return new Result(url, null, null);
        }

        static Result fail(Error error, @Nullable String detail)
        {
            return new Result(null, error, detail);
        }

        public boolean isOk()
        {
            return url != null;
        }
    }

    private UrlInput() {}

    /** 地址栏里敲的东西 → 要打开的网址 */
    public static Result fromAddressBar(String typed)
    {
        if(typed == null)
            return Result.fail(Error.EMPTY, null);
        String s = typed.trim();
        if(s.isEmpty())
            return Result.fail(Error.EMPTY, null);
        if(s.length() > MAX_LENGTH)
            return Result.fail(Error.TOO_LONG, null);
        if(s.equalsIgnoreCase(HOME))
            return Result.ok(HOME);
        // 只写了 IPv6 地址（不带方括号）
        if(!s.startsWith("[") && Ipv6Address.tryParse(s) != null)
            return normalize("http://[" + s + "]/");
        Matcher m = SCHEME.matcher(s);
        if(m.matches() && !s.startsWith("["))
        {
            String scheme = m.group(1).toLowerCase(Locale.ROOT);
            String rest = m.group(2);
            if(scheme.equals("http") || scheme.equals("https"))
                return normalize(s);
            // “主机:端口” 不是协议：冒号后面是数字（到 / ? # 或结尾为止）
            if(!rest.matches("\\d+([/?#].*)?") )
                return Result.fail(Error.UNSUPPORTED_SCHEME, scheme);
        }
        return normalize("http://" + s);
    }

    /** 规范化一个绝对网址（http / https）；不支持的返回错误 */
    public static Result normalize(String absolute)
    {
        if(absolute.length() > MAX_LENGTH)
            return Result.fail(Error.TOO_LONG, null);
        String s = absolute.trim();
        boolean secure;
        if(s.regionMatches(true, 0, "http://", 0, 7))
        {
            secure = false;
            s = s.substring(7);
        }
        else if(s.regionMatches(true, 0, "https://", 0, 8))
        {
            secure = true;
            s = s.substring(8);
        }
        else
        {
            Matcher m = SCHEME.matcher(s);
            if(m.matches())
                return Result.fail(Error.UNSUPPORTED_SCHEME, m.group(1).toLowerCase(Locale.ROOT));
            return Result.fail(Error.MALFORMED, null);
        }
        int hash = s.indexOf('#');
        if(hash >= 0)
            s = s.substring(0, hash);
        int end = s.length();
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if(c == '/' || c == '?')
            {
                end = i;
                break;
            }
        }
        String authority = s.substring(0, end);
        String rest = s.substring(end);
        if(authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('\\') >= 0)
            return Result.fail(Error.MALFORMED, null);
        String host;
        String portText = null;
        if(authority.startsWith("["))
        {
            int close = authority.indexOf(']');
            if(close < 0)
                return Result.fail(Error.MALFORMED, null);
            Ipv6Address a = Ipv6Address.tryParse(authority.substring(1, close));
            if(a == null)
                return Result.fail(Error.MALFORMED, null);
            host = "[" + a + "]";
            String after = authority.substring(close + 1);
            if(after.startsWith(":"))
                portText = after.substring(1);
            else if(!after.isEmpty())
                return Result.fail(Error.MALFORMED, null);
        }
        else
        {
            int colon = authority.lastIndexOf(':');
            String name = colon >= 0 ? authority.substring(0, colon) : authority;
            if(colon >= 0)
                portText = authority.substring(colon + 1);
            String ascii;
            try
            {
                ascii = IDN.toASCII(name, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            }
            catch(IllegalArgumentException e)
            {
                return Result.fail(Error.MALFORMED, null);
            }
            if(ascii.endsWith("."))
                ascii = ascii.substring(0, ascii.length() - 1);
            if(ascii.isEmpty() || ascii.length() > 253 || !HOST_NAME.matcher(ascii).matches())
                return Result.fail(Error.MALFORMED, null);
            host = ascii;
        }
        String port = "";
        if(portText != null)
        {
            if(!portText.matches("\\d{1,5}"))
                return Result.fail(Error.MALFORMED, null);
            int p = Integer.parseInt(portText);
            if(p < 1 || p > 65535)
                return Result.fail(Error.MALFORMED, null);
            if(p != (secure ? 443 : 80))
                port = ":" + p;
        }
        String path = rest.isEmpty() ? "/" : (rest.startsWith("?") ? "/" + rest : rest);
        String url = (secure ? "https://" : "http://") + host + port + encodePath(path);
        if(url.length() > MAX_LENGTH)
            return Result.fail(Error.TOO_LONG, null);
        return Result.ok(url);
    }

    /**
     * 页面里的链接、表单地址、图片地址相对 {@code base} 解析成绝对网址；解析不了、或不是 http / https 时返回 null。
     */
    @Nullable
    public static String resolve(String base, String reference)
    {
        if(reference == null)
            return null;
        String ref = reference.trim();
        if(ref.length() > MAX_LENGTH)
            return null;
        if(ref.equalsIgnoreCase(HOME))
            return HOME;
        Matcher m = SCHEME.matcher(ref);
        if(m.matches())
        {
            Result r = normalize(ref);
            return r.url;
        }
        if(base == null || !(base.startsWith("http://") || base.startsWith("https://")))
            return null;
        try
        {
            URI b = new URI(base);
            String encoded = encodePath(ref.isEmpty() ? "" : ref);
            int hash = encoded.indexOf('#');
            if(hash >= 0)
                encoded = encoded.substring(0, hash);
            if(encoded.isEmpty())
                return base;
            if(encoded.startsWith("?"))
            {
                // java.net.URI 按 RFC 2396 会丢掉最后一段路径，RFC 3986 是保留路径、只换查询
                int q = base.indexOf('?');
                return normalize((q < 0 ? base : base.substring(0, q)) + encoded).url;
            }
            URI r = b.resolve(new URI(encoded));
            return normalize(r.toString()).url;
        }
        catch(URISyntaxException | IllegalArgumentException e)
        {
            return null;
        }
    }

    /** 路径与查询：保留 RFC 3986 允许的字符和已有的 %XX，其余按 UTF-8 写成 %XX */
    static String encodePath(String s)
    {
        StringBuilder sb = new StringBuilder();
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for(int i = 0; i < b.length; i++)
        {
            int c = b[i] & 0xFF;
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~:/?#[]@!$&'()*+,;=".indexOf(c) >= 0;
            if(c == '%' && i + 2 < b.length && isHex(b[i + 1]) && isHex(b[i + 2]))
                allowed = true;
            if(allowed)
                sb.append((char) c);
            else
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return sb.toString();
    }

    private static boolean isHex(byte b)
    {
        return Character.digit((char) (b & 0xFF), 16) >= 0;
    }

    /**
     * 网址的主机部分（小写；IPv6 地址带方括号，不带端口）；不是 http / https 网址时返回 null。
     * 给规范化过的网址用（{@link #normalize} 的结果）。
     */
    @Nullable
    public static String hostOf(@Nullable String url)
    {
        if(url == null)
            return null;
        String s;
        if(url.regionMatches(true, 0, "http://", 0, 7))
            s = url.substring(7);
        else if(url.regionMatches(true, 0, "https://", 0, 8))
            s = url.substring(8);
        else
            return null;
        int end = s.length();
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if(c == '/' || c == '?' || c == '#')
            {
                end = i;
                break;
            }
        }
        String authority = s.substring(0, end);
        if(authority.startsWith("["))
        {
            int close = authority.indexOf(']');
            return close < 0 ? null : authority.substring(0, close + 1).toLowerCase(Locale.ROOT);
        }
        int colon = authority.lastIndexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }

    /** 两个网址是不是同一主机（不管协议、端口）：页面里的图片只从页面所在的网站取 */
    public static boolean sameHost(@Nullable String a, @Nullable String b)
    {
        String ha = hostOf(a);
        return ha != null && ha.equals(hostOf(b));
    }

    public static boolean isSecure(String url)
    {
        return url != null && url.startsWith("https://");
    }
}
