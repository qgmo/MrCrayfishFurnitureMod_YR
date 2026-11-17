package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.tls.TlsTrust;
import moe.qingu.youran.server.net.tls.CertificateBuilder;
import moe.qingu.youran.server.net.tls.IntermediateCa;
import moe.qingu.youran.api.network.ca.CertificateAuthority;
import moe.qingu.youran.api.network.ca.CertificateClient;
import moe.qingu.youran.api.network.ca.CertificateOrder;
import moe.qingu.youran.api.network.ca.Challenge;
import moe.qingu.youran.api.network.tls.CertificateTlsContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 网站注册接口：请求解析、回应格式、经游戏内 TCP / TLS 的完整往返（客户端协议栈与服务端协议栈互通） */
class WebServerTest
{
    static final Ipv6Address CLIENT = Ipv6Address.parse("fd00::c");
    static final Ipv6Address SERVER = Ipv6Address.parse("fd00:0:0:1::5");

    final ManualLoop loop = new ManualLoop();
    final Map<Ipv6Address, NetStack> hosts = new HashMap<>();
    NetStack client = host(CLIENT);
    NetStack server = host(SERVER);

    NetStack host(Ipv6Address a)
    {
        NetStack s = new NetStack(loop, p ->
        {
            NetStack dst = hosts.get(Ipv6Packet.destinationOf(p));
            if(dst != null)
                loop.schedule(() -> dst.receive(p), 1);
        }, new Random(a.hashCode()));
        s.addAddress(a);
        hosts.put(a, s);
        return s;
    }

    static final WebContext CONTEXT = new WebContext()
    {
        @Override
        public Ipv6Address address()
        {
            return SERVER;
        }

        @Override
        public <T> CompletableFuture<T> onMainThread(Supplier<T> task)
        {
            return CompletableFuture.completedFuture(task.get());
        }
    };

    /** 回显请求的网站 */
    static final WebHandler ECHO = (req, ctx) ->
    {
        if(req.path.equals("/go"))
            return CompletableFuture.completedFuture(WebResponse.seeOther("/done"));
        if(req.path.equals("/boom"))
            throw new IllegalStateException("boom");
        String s = "<html><body><p>" + Html.escape(req.method + " " + req.path + " q=" + req.query + " name=" + req.parameter("name")
                + " from=" + req.remoteAddress + " secure=" + req.secure + " host=" + req.host()) + "</p></body></html>";
        return CompletableFuture.completedFuture(WebResponse.xhtml(s));
    };

    @Test
    void parsesRequests()
    {
        HttpRequestParser.Result r = HttpRequestParser.tryParse("POST /buy?x=1 HTTP/1.1\r\nHost: minebay.yr\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 7\r\n\r\ni=3&t=a".getBytes(StandardCharsets.ISO_8859_1));
        assertFalse(r.isError());
        assertEquals("POST", r.method);
        assertEquals("/buy?x=1", r.target);
        assertEquals("i=3&t=a", new String(r.body, StandardCharsets.US_ASCII));
        WebRequest w = new WebRequest(r.method, r.target, r.headers, r.body, CLIENT, 1234, true);
        assertEquals("/buy", w.path);
        assertEquals("3", w.parameter("i"));
        assertEquals("1", w.parameter("x"));
        assertEquals("minebay.yr", w.host());
        // 不完整
        assertNull(HttpRequestParser.tryParse("GET / HTTP/1.1\r\nHost: a".getBytes(StandardCharsets.ISO_8859_1)));
        assertNull(HttpRequestParser.tryParse("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nab".getBytes(StandardCharsets.ISO_8859_1)));
        // 出错
        assertEquals(400, HttpRequestParser.tryParse("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)).errorStatus, "1.1 没有 Host");
        assertEquals(400, HttpRequestParser.tryParse("GET http://x/ HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)).errorStatus);
        assertEquals(400, HttpRequestParser.tryParse("garbage\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)).errorStatus);
        assertEquals(501, HttpRequestParser.tryParse("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)).errorStatus);
        assertEquals(413, HttpRequestParser.tryParse(("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: " + (HttpRequestParser.MAX_BODY + 1) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)).errorStatus);
        byte[] huge = new byte[HttpRequestParser.MAX_HEADER + 10];
        Arrays.fill(huge, (byte) 'a');
        assertEquals(431, HttpRequestParser.tryParse(huge).errorStatus);
    }

    @Test
    void buildsResponses()
    {
        ZonedDateTime t = ZonedDateTime.of(2026, 11, 14, 8, 0, 0, 0, ZoneOffset.UTC);
        String s = new String(WebServer.build(WebResponse.seeOther("/done"), false, t), StandardCharsets.UTF_8);
        assertTrue(s.startsWith("HTTP/1.1 303 See Other\r\nDate: Sat, 14 Nov 2026 08:00:00 GMT\r\n"));
        assertTrue(s.contains("\r\nLocation: /done\r\n"));
        assertTrue(s.contains("\r\nConnection: close\r\n\r\n"));
        // 首部里不许换行注入
        WebResponse bad = new WebResponse(200, "OK", WebResponse.XHTML, new byte[0], Collections.singletonMap("X-A", "1\r\nSet-Cookie: x"));
        assertFalse(new String(WebServer.build(bad, false, t), StandardCharsets.UTF_8).contains("Set-Cookie"));
    }

    @Test
    void servesOverHttp() throws Exception
    {
        WebServer.serve(server, ECHO, CONTEXT, null);
        DnsResolver dns = new DnsResolver(client, Ipv6Address.parse("fd00::53"));
        CompletableFuture<HttpClient.Response> get = HttpClient.send(client, dns, HttpClient.Url.parse("http://[" + SERVER + "]/x?y=1"), "GET", null, null, 10_000, null);
        loop.advance(5000);
        HttpClient.Response r = done(get);
        assertEquals(200, r.status);
        assertEquals(WebResponse.XHTML, r.header("content-type"));
        assertTrue(r.bodyText().contains("GET /x q=y=1 name=null from=" + CLIENT + " secure=false"));
        CompletableFuture<HttpClient.Response> post = HttpClient.send(client, dns, HttpClient.Url.parse("http://[" + SERVER + "]/form"), "POST",
                Collections.singletonMap("Content-Type", "application/x-www-form-urlencoded"), "name=%E5%BC%A0%E4%B8%89".getBytes(StandardCharsets.US_ASCII), 10_000, null);
        loop.advance(5000);
        assertTrue(done(post).bodyText().contains("POST /form q= name=张三"));
        CompletableFuture<HttpClient.Response> redirect = HttpClient.send(client, dns, HttpClient.Url.parse("http://[" + SERVER + "]/go"), "GET", null, null, 10_000, null);
        loop.advance(5000);
        assertEquals(303, done(redirect).status);
        assertEquals("/done", done(redirect).header("location"));
        CompletableFuture<HttpClient.Response> boom = HttpClient.send(client, dns, HttpClient.Url.parse("http://[" + SERVER + "]/boom"), "GET", null, null, 10_000, null);
        loop.advance(5000);
        assertEquals(500, done(boom).status);
    }

    @Test
    void servesOverHttps() throws Exception
    {
        long now = System.currentTimeMillis();
        long day = 24L * 3600 * 1000;
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPair rootKey = rsa.generateKeyPair();
        X509Certificate root = new CertificateBuilder().subject("测试根").publicKey(rootKey.getPublic()).selfSigned(rootKey.getPrivate())
                .validity(new Date(now - day), new Date(now + 3650 * day)).ca(-1).build();
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair caKey = ec.generateKeyPair();
        X509Certificate ca = new CertificateBuilder().subject("测试中间 CA").publicKey(caKey.getPublic()).issuer(root, rootKey.getPrivate())
                .validity(new Date(now - day), new Date(now + 365 * day)).ca(0).permitDns("yr").excludeAllIp().build();
        // 证书由测试中间 CA 签给 minebay.yr（正式运行时由网站向证书签发服务申请）
        KeyPair leafKey = ec.generateKeyPair();
        CertificateTlsContext tls = new CertificateTlsContext();
        tls.set(leafKey.getPrivate(), new IntermediateCa(Arrays.asList(ca), caKey.getPrivate())
                .issue(Collections.singletonList("minebay.yr"), leafKey.getPublic(), new Date(now)));
        WebServer s = WebServer.serve(server, ECHO, CONTEXT, tls);
        assertTrue(s.hasTls());
        // 用 IP 直连但按域名握手：自己拼一个“解析器”不方便，直接走 TLS 连接 + 手写请求
        DnsResolver dns = new DnsResolver(client, Ipv6Address.parse("fd00::53"));
        CompletableFuture<HttpClient.Response> wrongName = HttpClient.send(client, dns, HttpClient.Url.parse("https://[" + SERVER + "]/x"), "GET", null, null, 10_000, TlsTrust.of(root));
        loop.advance(5000);
        assertTrue(wrongName.isCompletedExceptionally(), "按 IP 访问：证书里没有这个名字");
        CompletableFuture<moe.qingu.youran.api.network.tls.TlsConnection> c = moe.qingu.youran.api.network.tls.TlsConnection.connect(client, SERVER, 443, "minebay.yr", TlsTrust.of(root),
                new moe.qingu.youran.api.network.tls.TlsConnection.Listener()
                {
                    final StringBuilder got = new StringBuilder();

                    @Override
                    public void onData(moe.qingu.youran.api.network.tls.TlsConnection connection, byte[] data)
                    {
                        got.append(new String(data, StandardCharsets.UTF_8));
                        if(got.toString().contains("</html>"))
                            received.complete(got.toString());
                    }
                });
        loop.advance(5000);
        done(c).write("GET /tls HTTP/1.1\r\nHost: minebay.yr\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        loop.advance(5000);
        assertTrue(received.isDone());
        assertTrue(done(received).contains("GET /tls q= name=null from=" + CLIENT + " secure=true host=minebay.yr"));
    }

    final CompletableFuture<String> received = new CompletableFuture<>();

    @Test
    void answersCertificateChallengesOnPort80() throws Exception
    {
        WebServer.serve(server, ECHO, CONTEXT, null);
        // 假的证书服务：像真的一样经网络 GET 认证网址（用 IP 直连，省掉 DNS），内容对就算通过（不签证书，只看认证）
        String[] seen = new String[2];
        CertificateAuthority fake = new CertificateAuthority()
        {
            CertificateOrder order;

            @Override
            public String unavailableReason()
            {
                return null;
            }

            @Override
            public CompletableFuture<CertificateOrder> newOrder(Ipv6Address requester, java.util.Collection<String> names, java.security.PublicKey key)
            {
                Challenge c = new Challenge("minebay.yr", "tok123", "tok123.content", "dnsvalue");
                order = new CertificateOrder("o1", CertificateOrder.Status.PENDING, Collections.singletonList("minebay.yr"),
                        Collections.singletonList(c), null, null, null, 0);
                return CompletableFuture.completedFuture(order);
            }

            @Override
            public CompletableFuture<CertificateOrder> order(String id)
            {
                return CompletableFuture.completedFuture(order);
            }

            @Override
            public CompletableFuture<CertificateOrder> ready(String id, Challenge.Method method)
            {
                seen[0] = method.id;
                DnsResolver dns = new DnsResolver(client, Ipv6Address.parse("fd00::53"));
                return HttpClient.send(client, dns, HttpClient.Url.parse("http://[" + SERVER + "]" + Challenge.HTTP_PATH + "tok123"), "GET", null, null, 10_000, null)
                        .thenApply(r ->
                        {
                            seen[1] = r.status + " " + r.bodyText();
                            return new CertificateOrder("o1", CertificateOrder.Status.INVALID, order.names, order.challenges, method, "只看认证", null, 0);
                        });
            }
        };
        CertificateClient.start(server, Collections.singletonList("minebay.yr"), null, fake, System::currentTimeMillis);
        loop.advance(5000);
        assertEquals("http", seen[0], "80 口开着网站：HTTP 认证");
        assertEquals("200 tok123.content", seen[1]);
        // 认证完以后交回网站自己处理
        DnsResolver dns = new DnsResolver(client, Ipv6Address.parse("fd00::53"));
        CompletableFuture<HttpClient.Response> after = HttpClient.send(client, dns, HttpClient.Url.parse("http://[" + SERVER + "]" + Challenge.HTTP_PATH + "tok123"),
                "GET", null, null, 10_000, null);
        loop.advance(5000);
        assertTrue(done(after).bodyText().contains("GET " + Challenge.HTTP_PATH + "tok123"));
    }

    /** 不阻塞：没完成就算失败 */
    static <T> T done(CompletableFuture<T> f) throws Exception
    {
        assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }
}
