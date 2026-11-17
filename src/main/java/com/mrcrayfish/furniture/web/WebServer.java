package com.mrcrayfish.furniture.web;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.ca.HttpChallenges;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.tls.TlsConnection;
import moe.qingu.youran.api.network.tls.TlsServerContext;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 在一个服务端协议栈上提供网站：80 口 HTTP，有 TLS 时 443 口再开 HTTPS（证书由网站自己向 YouranMod 的证书签发服务申请，
 * 见 {@code api.network.ca.CertificateClient}；80 口自动回证书服务的 HTTP 认证）。每条连接处理一个请求，回完就关
 * （{@code Connection: close}）；{@value #IDLE_TIMEOUT_MS} 毫秒内没收齐请求就断开，网站 {@value #HANDLER_TIMEOUT_MS} 毫秒内没回就回 503。
 * 跑在协议栈的事件循环（网络线程）上。
 */
public final class WebServer
{
    private static final Logger LOG = LogManager.getLogger("cfm");
    public static final int HTTP_PORT = 80;
    public static final int HTTPS_PORT = 443;
    static final long IDLE_TIMEOUT_MS = 15_000;
    static final long HANDLER_TIMEOUT_MS = 30_000;
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ROOT);

    private final NetStack stack;
    private final WebHandler handler;
    private final WebContext context;
    @Nullable
    private final TlsServerContext tls;

    private WebServer(NetStack stack, WebHandler handler, WebContext context, @Nullable TlsServerContext tls)
    {
        this.stack = stack;
        this.handler = handler;
        this.context = context;
        this.tls = tls;
    }

    /** 开始在 {@code stack} 上提供网站（在它的事件循环上调用）。{@code tls} 为 null 时只有 HTTP */
    public static WebServer serve(NetStack stack, WebHandler handler, WebContext context, @Nullable TlsServerContext tls)
    {
        WebServer s = new WebServer(stack, handler, context, tls);
        stack.listen(null, HTTP_PORT, c -> s.acceptPlain(c));
        if(tls != null)
        {
            stack.listen(null, HTTPS_PORT, c -> s.acceptTls(c));
        }
        return s;
    }

    public boolean hasTls()
    {
        return tls != null;
    }

    /** 不再接新连接 */
    public void stop()
    {
        stack.stopListening(null, HTTP_PORT);
        if(tls != null)
            stack.stopListening(null, HTTPS_PORT);
    }

    private interface Out
    {
        void write(byte[] d);

        void close();

        void abort();
    }

    private void acceptPlain(TcpConnection c)
    {
        Session s = new Session(new Out()
        {
            @Override
            public void write(byte[] d)
            {
                c.write(d);
            }

            @Override
            public void close()
            {
                c.close();
            }

            @Override
            public void abort()
            {
                c.abort();
            }
        }, c.remoteAddress(), c.remotePort(), false);
        c.setListener(new TcpConnection.Listener()
        {
            @Override
            public void onData(TcpConnection connection, byte[] data)
            {
                s.onData(data);
            }

            @Override
            public void onEnd(TcpConnection connection)
            {
                s.onEnd();
            }

            @Override
            public void onClosed(TcpConnection connection, NetworkException error)
            {
                s.onClosed();
            }
        });
        s.start();
    }

    private void acceptTls(TcpConnection c)
    {
        TlsConnection[] holder = new TlsConnection[1];
        Session s = new Session(new Out()
        {
            @Override
            public void write(byte[] d)
            {
                holder[0].write(d);
            }

            @Override
            public void close()
            {
                holder[0].close();
            }

            @Override
            public void abort()
            {
                holder[0].abort();
            }
        }, c.remoteAddress(), c.remotePort(), true);
        holder[0] = TlsConnection.accept(c, tls, new TlsConnection.Listener()
        {
            @Override
            public void onData(TlsConnection connection, byte[] data)
            {
                s.onData(data);
            }

            @Override
            public void onEnd(TlsConnection connection)
            {
                s.onEnd();
            }

            @Override
            public void onClosed(TlsConnection connection, NetworkException error)
            {
                s.onClosed();
            }
        });
        s.start();
    }

    private final class Session
    {
        private final Out out;
        private final Ipv6Address remote;
        private final int remotePort;
        private final boolean secure;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private EventLoop.Timer idle;
        private boolean answered;
        private boolean closed;

        Session(Out out, Ipv6Address remote, int remotePort, boolean secure)
        {
            this.out = out;
            this.remote = remote;
            this.remotePort = remotePort;
            this.secure = secure;
        }

        void start()
        {
            idle = stack.loop().schedule(out::abort, IDLE_TIMEOUT_MS);
        }

        void onData(byte[] data)
        {
            if(answered)
                return;
            buf.write(data, 0, data.length);
            HttpRequestParser.Result r = HttpRequestParser.tryParse(buf.toByteArray());
            if(r == null)
                return;
            answered = true;
            idle.cancel();
            if(r.isError())
            {
                send(WebResponse.error(r.errorStatus, r.errorReason), false);
                return;
            }
            boolean head = r.method.equals("HEAD");
            // 证书服务的 HTTP 认证（只在不加密的 80 口）：这台主机正在等的令牌由这里回
            String challenge = secure || !(head || r.method.equals("GET")) ? null : HttpChallenges.answer(stack, pathOf(r.target));
            if(challenge != null)
            {
                send(new WebResponse(200, "OK", "text/plain; charset=utf-8", challenge.getBytes(StandardCharsets.UTF_8),
                        java.util.Collections.<String, String>emptyMap()), head);
                return;
            }
            WebRequest request = new WebRequest(head ? "GET" : r.method, r.target, r.headers, r.body, remote, remotePort, secure);
            CompletableFuture<WebResponse> f;
            try
            {
                f = handler.handle(request, context);
            }
            catch(RuntimeException e)
            {
                LOG.error("Website handler failed for {}", r.target, e);
                send(WebResponse.error(500, "Internal Server Error"), head);
                return;
            }
            EventLoop.Timer timeout = stack.loop().schedule(() -> f.complete(WebResponse.error(503, "Service Unavailable")), HANDLER_TIMEOUT_MS);
            f.whenComplete((resp, err) -> stack.loop().execute(() ->
            {
                timeout.cancel();
                if(err != null || resp == null)
                {
                    Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                    if(cause != null)
                        LOG.error("Website handler failed for {}", r.target, cause);
                    send(WebResponse.error(500, "Internal Server Error"), head);
                }
                else
                {
                    send(resp, head);
                }
            }));
        }

        void onEnd()
        {
            if(!answered)
            {
                answered = true;
                idle.cancel();
                out.close();
            }
        }

        void onClosed()
        {
            closed = true;
            if(idle != null)
                idle.cancel();
        }

        private void send(WebResponse resp, boolean headOnly)
        {
            if(closed)
                return;
            out.write(build(resp, headOnly, ZonedDateTime.now(ZoneOffset.UTC)));
            out.close();
        }
    }

    /** 请求目标里 {@code ?} 之前的部分 */
    static String pathOf(String target)
    {
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    static byte[] build(WebResponse r, boolean headOnly, ZonedDateTime now)
    {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason).append("\r\n");
        h.append("Date: ").append(HTTP_DATE.format(now)).append("\r\n");
        h.append("Server: cfm\r\n");
        h.append("Content-Type: ").append(r.contentType).append("\r\n");
        h.append("Content-Length: ").append(r.body.length).append("\r\n");
        for(Map.Entry<String, String> e : r.headers.entrySet())
        {
            String n = e.getKey();
            String v = e.getValue();
            if(!n.matches("[A-Za-z0-9-]+") || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0)
                continue;
            h.append(n).append(": ").append(v).append("\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        if(headOnly)
            return hb;
        byte[] out = new byte[hb.length + r.body.length];
        System.arraycopy(hb, 0, out, 0, hb.length);
        System.arraycopy(r.body, 0, out, hb.length, r.body.length);
        return out;
    }
}
