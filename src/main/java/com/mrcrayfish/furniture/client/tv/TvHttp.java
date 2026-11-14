package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 电视视频的 HTTP 请求（悠然服务器魔改）。所有请求都：
 * <ul>
 * <li>先过 {@link HostPolicy}（https + 白名单域名），跳转不自动跟，每一跳都重新核对；</li>
 * <li>走客户端配置的代理（HTTP 或 SOCKS5），不填就直连；不改全局的代理属性；</li>
 * <li>限制连接、读取超时与响应大小；</li>
 * <li>只有发往 bilibili 接口时才带 Cookie；接口与 CDN 带 bilibili 的 Referer 与浏览器 UA（防盗链要求）。</li>
 * </ul>
 */
public final class TvHttp
{
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    public static final String REFERER = "https://www.bilibili.com/";
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int MAX_REDIRECTS = 3;

    private TvHttp() {}

    /** 配置改了（代理、cookie）：没有要清的缓存，留着给以后用 */
    static void onConfigChanged() {}

    public static final class Response implements Closeable
    {
        public final int status;
        public final long contentLength;
        @Nullable
        public final String contentRange;
        @Nullable
        public final String contentType;
        @Nullable
        public final String contentEncoding;
        public final URI uri;
        private final HttpURLConnection connection;
        private final long maxBytes;

        Response(HttpURLConnection connection, URI uri, long maxBytes) throws IOException
        {
            this.connection = connection;
            this.uri = uri;
            this.maxBytes = maxBytes;
            this.status = connection.getResponseCode();
            this.contentLength = connection.getContentLengthLong();
            this.contentRange = connection.getHeaderField("Content-Range");
            this.contentType = connection.getHeaderField("Content-Type");
            this.contentEncoding = connection.getHeaderField("Content-Encoding");
        }

        /** 响应体（超过上限时读取抛 IOException） */
        public InputStream body() throws IOException
        {
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if(in == null)
                in = new ByteArrayInputStream(new byte[0]);
            return maxBytes < 0 ? in : new LimitedInputStream(in, maxBytes);
        }

        @Override
        public void close()
        {
            connection.disconnect();
        }
    }

    /**
     * GET 请求。rangeStart ≥ 0 时带 Range 头（rangeEnd < 0 表示到结尾）；maxBytes < 0 表示不限大小（只用于转发视频，调用方自己限）。
     */
    public static Response get(URI uri, HostPolicy.Purpose purpose, @Nullable String accept, long rangeStart, long rangeEnd, long maxBytes, int readTimeoutMs) throws IOException
    {
        URI current = uri;
        for(int hop = 0; ; hop++)
        {
            if(!HostPolicy.allowed(current, purpose, TvDev.enabled()))
                throw new IOException("host not allowed: " + safe(current));
            HttpURLConnection c = open(current, purpose, accept, rangeStart, rangeEnd, readTimeoutMs);
            int code = c.getResponseCode();
            if(code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
            {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if(location == null || hop >= MAX_REDIRECTS)
                    throw new IOException("too many redirects from " + safe(uri));
                current = current.resolve(location.trim());
                continue;
            }
            Response res = new Response(c, current, maxBytes);
            if(maxBytes >= 0 && res.contentLength > maxBytes)
            {
                res.close();
                throw new IOException("response too large from " + safe(current));
            }
            return res;
        }
    }

    /** 取文本（JSON、弹幕 XML），自动解开 gzip / deflate；压缩前后都限大小 */
    public static String getText(URI uri, HostPolicy.Purpose purpose, long maxBytes) throws IOException
    {
        try(Response res = get(uri, purpose, "application/json, text/xml, */*", -1, -1, maxBytes, 15_000))
        {
            if(res.status != 200)
                throw new HttpStatusException(res.status, safe(res.uri));
            byte[] raw;
            try(InputStream in = res.body())
            {
                raw = readAll(in, maxBytes);
            }
            String encoding = res.contentEncoding == null ? "" : res.contentEncoding.trim().toLowerCase(java.util.Locale.ROOT);
            byte[] body;
            if(encoding.equals("gzip"))
                body = readAll(new GZIPInputStream(new ByteArrayInputStream(raw)), maxBytes * 8);
            else if(encoding.equals("deflate"))
                body = inflate(raw, maxBytes * 8);
            else
                body = raw;
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** bilibili 的弹幕用不带头的 deflate；不行再按 zlib 格式解 */
    static byte[] inflate(byte[] raw, long max) throws IOException
    {
        try
        {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater(true)), max);
        }
        catch(IOException e)
        {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater(false)), max);
        }
    }

    static byte[] readAll(InputStream in, long max) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long n = 0;
        int k;
        while((k = in.read(buf)) > 0)
        {
            n += k;
            if(n > max)
                throw new IOException("response too large");
            out.write(buf, 0, k);
        }
        return out.toByteArray();
    }

    private static HttpURLConnection open(URI uri, HostPolicy.Purpose purpose, @Nullable String accept, long rangeStart, long rangeEnd, int readTimeoutMs) throws IOException
    {
        URL url = uri.toURL();
        // 开发测试时的本机地址不走代理
        Proxy proxy = "127.0.0.1".equals(uri.getHost()) ? null : TvClientConfig.proxy(TvClientConfig.get());
        HttpURLConnection c = (HttpURLConnection) (proxy == null ? url.openConnection(Proxy.NO_PROXY) : url.openConnection(proxy));
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if(accept != null)
            c.setRequestProperty("Accept", accept);
        if(purpose == HostPolicy.Purpose.API || purpose == HostPolicy.Purpose.CDN || purpose == HostPolicy.Purpose.COVER)
            c.setRequestProperty("Referer", REFERER);
        if(purpose == HostPolicy.Purpose.API)
        {
            c.setRequestProperty("Accept-Encoding", "gzip, deflate");
            String cookie = TvCookies.header(TvClientConfig.get().cookie);
            if(!cookie.isEmpty())
                c.setRequestProperty("Cookie", cookie);
        }
        else
        {
            c.setRequestProperty("Accept-Encoding", "identity");
        }
        if(rangeStart >= 0)
            c.setRequestProperty("Range", "bytes=" + rangeStart + "-" + (rangeEnd >= 0 ? Long.toString(rangeEnd) : ""));
        return c;
    }

    /** 日志里只写协议、主机、路径，不写查询串（里面有签名、令牌） */
    public static String safe(@Nullable URI uri)
    {
        if(uri == null)
            return "null";
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    public static final class HttpStatusException extends IOException
    {
        public final int status;

        HttpStatusException(int status, String where)
        {
            super("HTTP " + status + " from " + where);
            this.status = status;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream
    {
        private long left;

        LimitedInputStream(InputStream in, long max)
        {
            super(in);
            this.left = max;
        }

        @Override
        public int read() throws IOException
        {
            int b = super.read();
            if(b >= 0 && --left < 0)
                throw new IOException("response too large");
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            int n = super.read(b, off, len);
            if(n > 0)
            {
                left -= n;
                if(left < 0)
                    throw new IOException("response too large");
            }
            return n;
        }
    }
}
