package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 经代理隧道发一次 HTTP/1.1 GET（悠然服务器魔改）：{@link TvProxy#open} 建隧道 → https 时 {@link TvProxy#tls} 套 TLS →
 * 发请求（{@code Connection: close}，一个连接只发一次）→ 解析状态行和头部，响应体按 Content-Length、chunked 或读到连接关闭。
 * 只给电视视频自己的请求用（{@link TvHttp}），不支持跳转（调用方每一跳重新核对白名单）。
 */
final class TunnelHttp
{
    private static final int MAX_HEAD = 64 * 1024;

    private TunnelHttp() {}

    /** 一次响应；body 读完或 close 时关掉连接 */
    static final class Result
    {
        final int status;
        final Map<String, String> headers;
        final InputStream body;
        final Socket socket;

        Result(int status, Map<String, String> headers, InputStream body, Socket socket)
        {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.socket = socket;
        }

        @Nullable
        String header(String name)
        {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        long contentLength()
        {
            String v = header("Content-Length");
            if(v == null || header("Transfer-Encoding") != null)
                return -1;
            try
            {
                return Long.parseLong(v.trim());
            }
            catch(NumberFormatException e)
            {
                return -1;
            }
        }
    }

    /**
     * headers 是请求头（不含 Host、Connection，这里加）。factory 为 null 时用默认的 TLS 工厂。
     */
    static Result get(TvProxy.Settings proxy, URI uri, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs, @Nullable SSLSocketFactory factory) throws IOException
    {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if(!https && !scheme.equals("http"))
            throw new IOException("unsupported scheme");
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
        Socket socket = TvProxy.open(proxy, host, port, connectTimeoutMs, readTimeoutMs);
        boolean ok = false;
        try
        {
            if(https)
                socket = TvProxy.tls(socket, host, port, factory);
            socket.setSoTimeout(readTimeoutMs);
            StringBuilder req = new StringBuilder();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if(uri.getRawQuery() != null)
                path += "?" + uri.getRawQuery();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            boolean defaultPort = uri.getPort() <= 0 || port == (https ? 443 : 80);
            req.append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n");
            for(Map.Entry<String, String> e : headers.entrySet())
                req.append(e.getKey()).append(": ").append(clean(e.getValue())).append("\r\n");
            req.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            Result head = readHead(in, socket);
            ok = true;
            return head;
        }
        finally
        {
            if(!ok)
                TvProxy.closeQuietly(socket);
        }
    }

    /** 读状态行和头部（跳过 1xx），决定响应体怎么读 */
    static Result readHead(InputStream in, Socket socket) throws IOException
    {
        while(true)
        {
            int total = 0;
            String statusLine = TvProxy.readLine(in, MAX_HEAD);
            total += statusLine.length();
            String[] parts = statusLine.split(" ", 3);
            if(parts.length < 2 || !parts[0].startsWith("HTTP/1."))
                throw new IOException("not an HTTP response");
            int status;
            try
            {
                status = Integer.parseInt(parts[1].trim());
            }
            catch(NumberFormatException e)
            {
                throw new IOException("not an HTTP response");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            while(true)
            {
                String line = TvProxy.readLine(in, MAX_HEAD);
                total += line.length() + 2;
                if(total > MAX_HEAD)
                    throw new IOException("response head too large");
                if(line.isEmpty())
                    break;
                int colon = line.indexOf(':');
                if(colon <= 0)
                    continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                // 同名头只留第一个（Content-Length 这种重复了本来就不对）
                headers.putIfAbsent(name, value);
            }
            if(status >= 100 && status < 200)
                continue;
            InputStream body;
            String te = headers.get("transfer-encoding");
            if(status == 204 || status == 304)
            {
                body = new ByteArrayInputStream(new byte[0]);
            }
            else if(te != null && te.toLowerCase(Locale.ROOT).contains("chunked"))
            {
                body = new ChunkedInputStream(in);
            }
            else if(headers.containsKey("content-length"))
            {
                long n;
                try
                {
                    n = Long.parseLong(headers.get("content-length").trim());
                }
                catch(NumberFormatException e)
                {
                    throw new IOException("bad Content-Length");
                }
                if(n < 0)
                    throw new IOException("bad Content-Length");
                body = new FixedInputStream(in, n);
            }
            else
            {
                body = in;
            }
            return new Result(status, headers, new ClosingInputStream(body, socket), socket);
        }
    }

    /** 头部的值去掉换行等控制字符（防止拼出多余的头） */
    private static String clean(String v)
    {
        StringBuilder sb = new StringBuilder(v.length());
        for(int i = 0; i < v.length(); i++)
        {
            char c = v.charAt(i);
            if(c >= 0x20 && c != 0x7F)
                sb.append(c);
        }
        return sb.toString();
    }

    /** 关掉流时关掉连接 */
    private static final class ClosingInputStream extends FilterInputStream
    {
        private final Socket socket;

        ClosingInputStream(InputStream in, Socket socket)
        {
            super(in);
            this.socket = socket;
        }

        @Override
        public void close()
        {
            TvProxy.closeQuietly(socket);
        }
    }

    /** 按 Content-Length 读，读不够就是连接断了 */
    private static final class FixedInputStream extends FilterInputStream
    {
        private long left;

        FixedInputStream(InputStream in, long length)
        {
            super(in);
            this.left = length;
        }

        @Override
        public int read() throws IOException
        {
            if(left <= 0)
                return -1;
            int b = super.read();
            if(b < 0)
                throw new EOFException("connection closed before the end of the response");
            left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            if(left <= 0)
                return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if(n < 0)
                throw new EOFException("connection closed before the end of the response");
            left -= n;
            return n;
        }

        @Override
        public int available() throws IOException
        {
            return (int) Math.min(super.available(), left);
        }
    }

    /** chunked 响应体 */
    static final class ChunkedInputStream extends InputStream
    {
        private final InputStream in;
        private long chunkLeft;
        private boolean done;

        ChunkedInputStream(InputStream in)
        {
            this.in = in;
        }

        private boolean nextChunk() throws IOException
        {
            if(done)
                return false;
            if(chunkLeft == 0)
            {
                String line = TvProxy.readLine(in, 1024);
                if(line.isEmpty())
                    line = TvProxy.readLine(in, 1024);
                int semi = line.indexOf(';');
                String size = (semi >= 0 ? line.substring(0, semi) : line).trim();
                try
                {
                    chunkLeft = Long.parseLong(size, 16);
                }
                catch(NumberFormatException e)
                {
                    throw new IOException("bad chunk size");
                }
                if(chunkLeft < 0)
                    throw new IOException("bad chunk size");
                if(chunkLeft == 0)
                {
                    // 跳过尾部的头
                    while(!TvProxy.readLine(in, MAX_HEAD).isEmpty())
                    {
                        // 跳过
                    }
                    done = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException
        {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            if(len == 0)
                return 0;
            if(!nextChunk())
                return -1;
            int n = in.read(b, off, (int) Math.min(len, chunkLeft));
            if(n < 0)
                throw new EOFException("connection closed inside a chunk");
            chunkLeft -= n;
            if(chunkLeft == 0)
            {
                // 每块后面的 CRLF
                TvProxy.readLine(in, 2);
            }
            return n;
        }
    }
}
