package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机转发（悠然服务器魔改）：视频字节由模组自己的 Java 代码从 bilibili CDN 取（Referer、UA、代理、地址过期后重取都在这里），
 * 再交给 FFmpeg。FFmpeg 只连这个只监听 127.0.0.1 的小 HTTP 服务，不直接联网。
 * <ul>
 * <li>每路流一个随机令牌的路径（{@code /<32 位十六进制>/s}），猜不到的路径一律 404；</li>
 * <li>只转发 {@link HostPolicy.Purpose#CDN} 白名单里的域名（由 {@link TvHttp} 核对，跳转也核对）；</li>
 * <li>每个响应都是 {@code Connection: close}，范围请求回上游给的精确 206（Content-Range、Content-Length），
 * 否则 FFmpeg 8.1.2 打开 DASH 文件时连接会被重置（原型实测）；</li>
 * <li>上游 403 / 404 / 410（地址过期）时调用 {@link Source#refresh()} 重新取流地址再试一次；</li>
 * <li>同时最多 {@value #MAX_CONNECTIONS} 个连接，请求头最多 8 KB，读写有超时，一路流最多转发 {@value #MAX_STREAM_BYTES} 字节。</li>
 * </ul>
 */
public final class LocalRelay
{
    public static final int MAX_CONNECTIONS = 16;
    public static final long MAX_STREAM_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_HEAD = 8192;
    private static final int TIMEOUT_MS = 20_000;
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]{1,15})-([0-9]{0,15})");
    private static final Pattern REQUEST = Pattern.compile("(GET|HEAD) /([0-9a-f]{32})/s HTTP/1\\.[01]");

    /** 一路流的来源：按顺序的候选地址；地址过期时重新取 */
    public interface Source
    {
        List<URI> urls();

        /** 重新取流地址（同一时间只做一次，短时间内重复调用直接返回） */
        void refresh() throws IOException;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();
    private static final Semaphore SLOTS = new Semaphore(MAX_CONNECTIONS);
    private static ServerSocket server;

    private LocalRelay() {}

    /** 登记一路流，返回给 FFmpeg 用的本机地址 */
    public static synchronized String register(Source source) throws IOException
    {
        ensureStarted();
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        String token = NativeLibs.hex(b);
        SOURCES.put(token, source);
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + token + "/s";
    }

    public static void unregister(@Nullable String url)
    {
        if(url == null)
            return;
        Matcher m = Pattern.compile("/([0-9a-f]{32})/s$").matcher(url);
        if(m.find())
            SOURCES.remove(m.group(1));
    }

    private static void ensureStarted() throws IOException
    {
        if(server != null && !server.isClosed())
            return;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(LocalRelay::acceptLoop, "cfm-tv-relay");
        t.setDaemon(true);
        t.start();
    }

    private static void acceptLoop()
    {
        ServerSocket s = server;
        while(!s.isClosed())
        {
            try
            {
                Socket socket = s.accept();
                if(!socket.getInetAddress().isLoopbackAddress() || !SLOTS.tryAcquire())
                {
                    socket.close();
                    continue;
                }
                Thread t = new Thread(() ->
                {
                    try
                    {
                        handle(socket);
                    }
                    finally
                    {
                        SLOTS.release();
                    }
                }, "cfm-tv-relay-conn");
                t.setDaemon(true);
                t.start();
            }
            catch(IOException e)
            {
                if(!s.isClosed())
                    MrCrayfishFurnitureMod.logger().warn("TV video relay accept failed: {}", e.toString());
            }
        }
    }

    private static void handle(Socket socket)
    {
        try(Socket sock = socket)
        {
            sock.setSoTimeout(TIMEOUT_MS);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String head = readHead(in);
            if(head == null)
                return;
            String[] lines = head.split("\r\n");
            Matcher req = REQUEST.matcher(lines[0]);
            if(!req.matches())
            {
                simple(out, 400, "Bad Request");
                return;
            }
            boolean headOnly = req.group(1).equals("HEAD");
            Source source = SOURCES.get(req.group(2));
            if(source == null)
            {
                simple(out, 404, "Not Found");
                return;
            }
            long start = -1, end = -1;
            for(int i = 1; i < lines.length; i++)
            {
                String line = lines[i];
                int colon = line.indexOf(':');
                if(colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("range"))
                {
                    Matcher r = RANGE.matcher(line.substring(colon + 1).trim());
                    if(!r.matches())
                    {
                        simple(out, 416, "Range Not Satisfiable");
                        return;
                    }
                    start = Long.parseLong(r.group(1));
                    end = r.group(2).isEmpty() ? -1 : Long.parseLong(r.group(2));
                    if(end >= 0 && end < start)
                    {
                        simple(out, 416, "Range Not Satisfiable");
                        return;
                    }
                }
            }
            forward(source, start, end, headOnly, out);
        }
        catch(SocketException e)
        {
            // FFmpeg 跳转时会主动断开，正常
        }
        catch(IOException | RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().debug("TV video relay: {}", e.toString());
        }
    }

    private static void forward(Source source, long start, long end, boolean headOnly, OutputStream out) throws IOException
    {
        IOException last = null;
        for(int round = 0; round < 2; round++)
        {
            for(URI uri : source.urls())
            {
                TvHttp.Response res;
                try
                {
                    res = TvHttp.get(uri, HostPolicy.Purpose.CDN, "*/*", start, end, -1, TIMEOUT_MS);
                }
                catch(IOException e)
                {
                    last = e;
                    continue;
                }
                try
                {
                    if(res.status == 403 || res.status == 404 || res.status == 410)
                    {
                        last = new IOException("HTTP " + res.status + " from " + TvHttp.safe(res.uri));
                        continue;
                    }
                    if(res.status == 416)
                    {
                        String cr = res.contentRange;
                        write(out, "HTTP/1.1 416 Range Not Satisfiable\r\n" + (cr != null && cr.matches("bytes \\*/[0-9]+") ? "Content-Range: " + cr + "\r\n" : "") + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                        return;
                    }
                    long length = res.contentLength;
                    String header;
                    if(res.status == 206)
                    {
                        String cr = res.contentRange;
                        if(cr == null || length < 0 || !exactRange(cr, length))
                        {
                            last = new IOException("bad 206 from " + TvHttp.safe(res.uri));
                            continue;
                        }
                        header = "HTTP/1.1 206 Partial Content\r\nContent-Range: " + cr.trim() + "\r\n";
                    }
                    else if(res.status == 200 && start <= 0 && length >= 0)
                    {
                        header = "HTTP/1.1 200 OK\r\n";
                    }
                    else
                    {
                        last = new IOException("HTTP " + res.status + " from " + TvHttp.safe(res.uri));
                        continue;
                    }
                    if(length > MAX_STREAM_BYTES)
                        throw new IOException("stream too large");
                    write(out, header + "Content-Type: " + contentType(res.contentType) + "\r\nContent-Length: " + length + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n");
                    if(!headOnly)
                        copy(res, out, length);
                    out.flush();
                    return;
                }
                finally
                {
                    res.close();
                }
            }
            if(round == 0)
            {
                // 候选地址都不行（多半是过期了）：重新取一次流地址
                try
                {
                    source.refresh();
                }
                catch(IOException e)
                {
                    last = e;
                    break;
                }
            }
        }
        simple(out, 502, "Bad Gateway");
        if(last != null)
            MrCrayfishFurnitureMod.logger().info("TV video relay: upstream failed: {}", last.getMessage());
    }

    /** Content-Range 的长度与 Content-Length 一致 */
    static boolean exactRange(String contentRange, long length)
    {
        Matcher m = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matcher(contentRange.trim());
        if(!m.matches())
            return false;
        long a = Long.parseLong(m.group(1)), b = Long.parseLong(m.group(2));
        return b >= a && b - a + 1 == length;
    }

    private static String contentType(@Nullable String upstream)
    {
        if(upstream != null)
        {
            String t = upstream.toLowerCase(Locale.ROOT).trim();
            if(t.matches("(video|audio)/[a-z0-9.+-]{1,40}"))
                return t;
        }
        return "video/mp4";
    }

    private static void copy(TvHttp.Response res, OutputStream out, long length) throws IOException
    {
        try(InputStream in = res.body())
        {
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while(left > 0)
            {
                int k = in.read(buf, 0, (int) Math.min(buf.length, left));
                if(k < 0)
                    break;
                out.write(buf, 0, k);
                left -= k;
            }
        }
    }

    @Nullable
    private static String readHead(InputStream in) throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while(buf.size() < MAX_HEAD)
        {
            int b = in.read();
            if(b < 0)
                return null;
            buf.write(b);
            // 找 \r\n\r\n
            if((state == 0 || state == 2) && b == '\r')
                state++;
            else if((state == 1 || state == 3) && b == '\n')
                state++;
            else
                state = b == '\r' ? 1 : 0;
            if(state == 4)
                return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static void simple(OutputStream out, int code, String reason) throws IOException
    {
        write(out, "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        out.flush();
    }

    private static void write(OutputStream out, String s) throws IOException
    {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
