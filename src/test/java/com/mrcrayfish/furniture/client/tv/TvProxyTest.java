package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理账号密码（{@link TvProxy}、{@link TunnelHttp}）：本地起假的 HTTP 代理、SOCKS5 代理和目标服务器，
 * 测 CONNECT 带认证、SOCKS5 用户名 / 密码认证、认证失败的提示、隧道上套 TLS 校验证书与主机名、chunked 响应。
 */
class TvProxyTest
{
    private static final String USER = "玩家user";
    private static final String PASS = "p@ss:wörd 1";

    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();
    /** 假代理看到的东西（请求头、目标地址、账号） */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() throws IOException
    {
        for(ServerSocket s : servers)
            s.close();
    }

    // ================= 测试 =================

    @Test
    void httpConnectWithCredentials() throws Exception
    {
        int target = httpTarget("hello through http proxy");
        int proxy = httpProxy(target, true);
        TunnelHttp.Result r = TunnelHttp.get(settings(TvProxy.Type.HTTP, proxy, USER, PASS), URI.create("http://api.example.test/x?a=1"), headers(), 3000, 3000, null);
        assertEquals(200, r.status);
        assertEquals("hello through http proxy", read(r.body));
        assertTrue(seen.contains("CONNECT api.example.test:80 HTTP/1.1"), seen.toString());
        assertTrue(seen.contains("auth ok"), seen.toString());
        assertTrue(seen.contains("GET /x?a=1 HTTP/1.1"), seen.toString());
        assertTrue(seen.contains("Host: api.example.test"), seen.toString());
    }

    @Test
    void httpConnectWrongPassword() throws Exception
    {
        int target = httpTarget("x");
        int proxy = httpProxy(target, true);
        TvProxy.AuthException e = assertThrows(TvProxy.AuthException.class, () -> TunnelHttp.get(settings(TvProxy.Type.HTTP, proxy, USER, "wrong"), URI.create("http://a.test/"), headers(), 3000, 3000, null));
        assertTrue(e.getMessage().contains("407"));
        assertFalse(e.getMessage().contains("wrong"));
        assertFalse(e.getMessage().contains(USER));
    }

    @Test
    void httpConnectMissingCredentials() throws Exception
    {
        int target = httpTarget("x");
        int proxy = httpProxy(target, true);
        TvProxy.AuthException e = assertThrows(TvProxy.AuthException.class, () -> TunnelHttp.get(settings(TvProxy.Type.HTTP, proxy, "", ""), URI.create("http://a.test/"), headers(), 3000, 3000, null));
        assertTrue(e.getMessage().contains("requires"), e.getMessage());
    }

    @Test
    void httpConnectWithoutAuthProxy() throws Exception
    {
        int target = httpTarget("no auth needed");
        int proxy = httpProxy(target, false);
        TunnelHttp.Result r = TunnelHttp.get(settings(TvProxy.Type.HTTP, proxy, "", ""), URI.create("http://a.test/"), headers(), 3000, 3000, null);
        assertEquals("no auth needed", read(r.body));
        assertFalse(seen.stream().anyMatch(s -> s.startsWith("Proxy-Authorization")), seen.toString());
    }

    @Test
    void socks5WithCredentials() throws Exception
    {
        int target = httpTarget("hello through socks5");
        int proxy = socksProxy(target, true);
        TunnelHttp.Result r = TunnelHttp.get(settings(TvProxy.Type.SOCKS5, proxy, USER, PASS), URI.create("http://upos-sz-mirrorcos.bilivideo.com:8080/v.m4s"), headers(), 3000, 3000, null);
        assertEquals(200, r.status);
        assertEquals("hello through socks5", read(r.body));
        // 目标按域名交给代理（代理那头解析），用的是用户名 / 密码认证
        assertTrue(seen.contains("methods [2, 0]"), seen.toString());
        assertTrue(seen.contains("auth ok"), seen.toString());
        assertTrue(seen.contains("connect domain upos-sz-mirrorcos.bilivideo.com:8080"), seen.toString());
        assertTrue(seen.contains("Host: upos-sz-mirrorcos.bilivideo.com:8080"), seen.toString());
    }

    @Test
    void socks5WrongPassword() throws Exception
    {
        int target = httpTarget("x");
        int proxy = socksProxy(target, true);
        TvProxy.AuthException e = assertThrows(TvProxy.AuthException.class, () -> TunnelHttp.get(settings(TvProxy.Type.SOCKS5, proxy, USER, "wrong"), URI.create("http://a.test/"), headers(), 3000, 3000, null));
        assertFalse(e.getMessage().contains("wrong"));
        assertFalse(e.getMessage().contains(USER));
    }

    @Test
    void socks5MissingCredentials() throws Exception
    {
        int target = httpTarget("x");
        int proxy = socksProxy(target, true);
        TvProxy.AuthException e = assertThrows(TvProxy.AuthException.class, () -> TunnelHttp.get(settings(TvProxy.Type.SOCKS5, proxy, "", ""), URI.create("http://a.test/"), headers(), 3000, 3000, null));
        assertTrue(e.getMessage().contains("requires"), e.getMessage());
        assertTrue(seen.contains("methods [0]"), seen.toString());
    }

    @Test
    void socks5WithoutAuthProxy() throws Exception
    {
        int target = httpTarget("plain socks");
        int proxy = socksProxy(target, false);
        TunnelHttp.Result r = TunnelHttp.get(settings(TvProxy.Type.SOCKS5, proxy, USER, PASS), URI.create("http://a.test/"), headers(), 3000, 3000, null);
        assertEquals("plain socks", read(r.body));
    }

    @Test
    void unreachableProxy() throws Exception
    {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = s.getLocalPort();
        s.close();
        assertThrows(TvProxy.ProxyException.class, () -> TunnelHttp.get(settings(TvProxy.Type.HTTP, port, "", ""), URI.create("http://a.test/"), headers(), 3000, 3000, null));
    }

    @Test
    void tlsOverTunnelChecksCertificateAndHost() throws Exception
    {
        int target = tlsTarget("secret over tls");
        int http = httpProxy(target, true);
        int socks = socksProxy(target, true);
        SSLSocketFactory trusting = trustingFactory();
        // 证书是 localhost 的：对得上就能取到
        TunnelHttp.Result r = TunnelHttp.get(settings(TvProxy.Type.HTTP, http, USER, PASS), URI.create("https://localhost/a"), headers(), 3000, 3000, trusting);
        assertEquals("secret over tls", read(r.body));
        r = TunnelHttp.get(settings(TvProxy.Type.SOCKS5, socks, USER, PASS), URI.create("https://localhost/a"), headers(), 3000, 3000, trusting);
        assertEquals("secret over tls", read(r.body));
        // 代理把别的名字也接到同一台服务器：主机名对不上，握手失败
        assertThrows(SSLException.class, () -> TunnelHttp.get(settings(TvProxy.Type.HTTP, http, USER, PASS), URI.create("https://api.bilibili.com/a"), headers(), 3000, 3000, trusting));
        assertThrows(SSLException.class, () -> TunnelHttp.get(settings(TvProxy.Type.SOCKS5, socks, USER, PASS), URI.create("https://api.bilibili.com/a"), headers(), 3000, 3000, trusting));
        // 不信任这张自签证书（默认信任库）：握手失败
        assertThrows(SSLException.class, () -> TunnelHttp.get(settings(TvProxy.Type.HTTP, http, USER, PASS), URI.create("https://localhost/a"), headers(), 3000, 3000, (SSLSocketFactory) SSLSocketFactory.getDefault()));
    }

    @Test
    void chunkedAndHeaders() throws Exception
    {
        int target = rawTarget("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes 0-10/100\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nTrailer: x\r\n\r\n");
        int proxy = httpProxy(target, false);
        TunnelHttp.Result r = TunnelHttp.get(settings(TvProxy.Type.HTTP, proxy, "", ""), URI.create("http://a.test/"), headers(), 3000, 3000, null);
        assertEquals(206, r.status);
        assertEquals("video/mp4", r.header("content-type"));
        assertEquals("bytes 0-10/100", r.header("Content-Range"));
        assertEquals(-1, r.contentLength());
        assertEquals("hello world", read(r.body));
    }

    @Test
    void secretsStayOut()
    {
        TvProxy.Settings s = settings(TvProxy.Type.SOCKS5, 1080, USER, PASS);
        assertFalse(s.toString().contains(PASS));
        assertFalse(s.toString().contains(USER));
        assertTrue(s.hasCredentials());
        assertFalse(settings(TvProxy.Type.SOCKS5, 1080, "", "x").hasCredentials());
        assertEquals("Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8)), TvProxy.basic(USER, PASS));
        assertEquals("ab", TvClientConfig.cleanCredential("a\r\nb"));
        assertNotNull(TvProxy.ipLiteral("127.0.0.1"));
        assertNotNull(TvProxy.ipLiteral("[::1]"));
        assertNull(TvProxy.ipLiteral("api.bilibili.com"));
    }

    // ================= 假服务器 =================

    private static TvProxy.Settings settings(TvProxy.Type type, int port, String user, String pass)
    {
        return new TvProxy.Settings(type, "127.0.0.1", port, user, pass);
    }

    private static Map<String, String> headers()
    {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "test");
        return h;
    }

    private static String read(InputStream in) throws IOException
    {
        try(InputStream i = in)
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while((n = i.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private ServerSocket listen(ServerSocket s)
    {
        servers.add(s);
        return s;
    }

    private void serve(ServerSocket server, Handler handler)
    {
        Thread t = new Thread(() ->
        {
            while(!server.isClosed())
            {
                try
                {
                    Socket c = server.accept();
                    Thread w = new Thread(() ->
                    {
                        try(Socket sock = c)
                        {
                            sock.setSoTimeout(5000);
                            handler.handle(sock);
                        }
                        catch(IOException ignored)
                        {
                        }
                    });
                    w.setDaemon(true);
                    w.start();
                }
                catch(IOException e)
                {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private interface Handler
    {
        void handle(Socket s) throws IOException;
    }

    /** 读一个 HTTP 请求头，记下每一行 */
    private List<String> readHead(InputStream in) throws IOException
    {
        List<String> lines = new ArrayList<>();
        while(true)
        {
            String line = TvProxy.readLine(in, 8192);
            if(line.isEmpty())
                return lines;
            lines.add(line);
            seen.add(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
        }
    }

    /** 普通 HTTP 服务器：不管请求什么都回 body */
    private int httpTarget(String body) throws IOException
    {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return rawTarget("HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n" + body);
    }

    private int rawTarget(String response) throws IOException
    {
        ServerSocket s = listen(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        serve(s, c ->
        {
            readHead(c.getInputStream());
            c.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            c.getOutputStream().flush();
        });
        return s.getLocalPort();
    }

    /** TLS 服务器（证书 CN=localhost，SAN localhost） */
    private int tlsTarget(String body) throws Exception
    {
        SSLContext ctx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore(), "changeit".toCharArray());
        ctx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        listen(s);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n" + body;
        serve(s, c ->
        {
            readHead(c.getInputStream());
            c.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            c.getOutputStream().flush();
        });
        return s.getLocalPort();
    }

    private static KeyStore keyStore() throws Exception
    {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try(InputStream in = TvProxyTest.class.getResourceAsStream("/tv/tvproxy-test.p12"))
        {
            ks.load(in, "changeit".toCharArray());
        }
        return ks;
    }

    /** 只信任测试证书 */
    private static SSLSocketFactory trustingFactory() throws Exception
    {
        KeyStore trust = KeyStore.getInstance("JKS");
        trust.load(null, null);
        trust.setCertificateEntry("test", keyStore().getCertificate("test"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
    }

    /** 假 HTTP 代理：requireAuth 时核对 Proxy-Authorization，对了才建隧道；不管 CONNECT 到哪都接到 targetPort */
    private int httpProxy(int targetPort, boolean requireAuth) throws IOException
    {
        ServerSocket s = listen(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        String expected = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
        serve(s, c ->
        {
            List<String> head = readHead(c.getInputStream());
            OutputStream out = c.getOutputStream();
            if(requireAuth && !head.contains(new String(expected.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)))
            {
                out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            if(requireAuth)
                seen.add("auth ok");
            try(Socket up = new Socket(InetAddress.getLoopbackAddress(), targetPort))
            {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                pipe(c, up);
            }
        });
        return s.getLocalPort();
    }

    /** 假 SOCKS5 代理（RFC 1928 / 1929）：requireAuth 时只收用户名 / 密码认证 */
    private int socksProxy(int targetPort, boolean requireAuth) throws IOException
    {
        ServerSocket s = listen(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        serve(s, c ->
        {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            if(in.read() != 5)
                return;
            int n = in.read();
            List<Integer> methods = new ArrayList<>();
            for(int i = 0; i < n; i++)
                methods.add(in.read());
            seen.add("methods " + methods);
            if(requireAuth)
            {
                if(!methods.contains(2))
                {
                    out.write(new byte[]{5, (byte) 0xFF});
                    out.flush();
                    return;
                }
                out.write(new byte[]{5, 2});
                out.flush();
                in.read();
                byte[] user = new byte[in.read()];
                readFully(in, user);
                byte[] pass = new byte[in.read()];
                readFully(in, pass);
                boolean ok = USER.equals(new String(user, StandardCharsets.UTF_8)) && PASS.equals(new String(pass, StandardCharsets.UTF_8));
                out.write(new byte[]{1, (byte) (ok ? 0 : 1)});
                out.flush();
                if(!ok)
                    return;
                seen.add("auth ok");
            }
            else
            {
                out.write(new byte[]{5, 0});
                out.flush();
            }
            in.read();
            in.read();
            in.read();
            int atyp = in.read();
            String where;
            if(atyp == 3)
            {
                byte[] name = new byte[in.read()];
                readFully(in, name);
                where = "domain " + new String(name, StandardCharsets.US_ASCII);
            }
            else
            {
                byte[] ip = new byte[atyp == 1 ? 4 : 16];
                readFully(in, ip);
                where = "ip " + InetAddress.getByAddress(ip).getHostAddress();
            }
            int port = (in.read() << 8) | in.read();
            seen.add("connect " + where + ":" + port);
            try(Socket up = new Socket(InetAddress.getLoopbackAddress(), targetPort))
            {
                out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                out.flush();
                pipe(c, up);
            }
        });
        return s.getLocalPort();
    }

    private static void readFully(InputStream in, byte[] b) throws IOException
    {
        int off = 0;
        while(off < b.length)
        {
            int n = in.read(b, off, b.length - off);
            if(n < 0)
                throw new IOException("eof");
            off += n;
        }
    }

    /** 两边对拷，直到一边关掉 */
    private static void pipe(Socket a, Socket b) throws IOException
    {
        Thread t = new Thread(() -> copy(b, a));
        t.setDaemon(true);
        t.start();
        copy(a, b);
        try
        {
            t.join(5000);
        }
        catch(InterruptedException ignored)
        {
        }
    }

    private static void copy(Socket from, Socket to)
    {
        byte[] buf = new byte[8192];
        try
        {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while((n = in.read(buf)) > 0)
            {
                out.write(buf, 0, n);
                out.flush();
            }
            to.shutdownOutput();
        }
        catch(IOException ignored)
        {
        }
    }
}
