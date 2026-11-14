package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;

/**
 * 电视视频请求经代理建连接（悠然服务器魔改，服主 11-14：代理支持账号密码）。
 * <p>
 * 不用 Java 自带的代理支持：带认证的 SOCKS5 要改全局的 {@link java.net.Authenticator}，HTTP 代理认证隧道要改
 * {@code jdk.http.auth.*} 等全局属性，会影响别的模组。这里自己做：
 * <ul>
 * <li>HTTP 代理：发 {@code CONNECT 主机:端口}，有账号时带 {@code Proxy-Authorization: Basic}；</li>
 * <li>SOCKS5：按 RFC 1928 建连接，有账号时按 RFC 1929 做用户名 / 密码认证；目标一律按域名发给代理（代理那头解析）；</li>
 * <li>隧道建好后由调用方套 TLS（{@link #tls}，校验证书与主机名）。</li>
 * </ul>
 * 账号密码不写进任何异常信息、日志（{@link Settings#toString()} 也不带）。
 */
public final class TvProxy
{
    /** 代理回应的头部最多读这么多（防止代理没完没了地发） */
    private static final int MAX_REPLY_HEAD = 16 * 1024;

    private TvProxy() {}

    public enum Type
    {
        HTTP, SOCKS5
    }

    /** 一份代理设置（不可变）。toString 不带账号密码 */
    public static final class Settings
    {
        public final Type type;
        public final String host;
        public final int port;
        private final String username;
        private final String password;

        public Settings(Type type, String host, int port, @Nullable String username, @Nullable String password)
        {
            this.type = type;
            this.host = host;
            this.port = port;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        /** 填了用户名才认证（密码可以是空的） */
        public boolean hasCredentials()
        {
            return !username.isEmpty();
        }

        @Override
        public String toString()
        {
            return type + " proxy " + host + ":" + port + (hasCredentials() ? " (with credentials)" : "");
        }
    }

    /** 代理拒绝了账号密码，或者要账号密码而没填 */
    public static final class AuthException extends IOException
    {
        public AuthException(String message)
        {
            super(message);
        }
    }

    /** 连不上代理、代理那头连不上目标、或者不是我们认得的代理回应 */
    public static final class ProxyException extends IOException
    {
        public ProxyException(String message)
        {
            super(message);
        }
    }

    /**
     * 经代理连到 targetHost:targetPort，返回建好隧道的连接（还没套 TLS）。readTimeoutMs 同时用于等代理回应。
     */
    public static Socket open(Settings proxy, String targetHost, int targetPort, int connectTimeoutMs, int readTimeoutMs) throws IOException
    {
        // 明确不经任何全局代理（socksProxyHost 等系统属性）连代理本身
        Socket socket = new Socket(Proxy.NO_PROXY);
        boolean ok = false;
        try
        {
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            try
            {
                socket.connect(new InetSocketAddress(proxy.host, proxy.port), connectTimeoutMs);
            }
            catch(IOException e)
            {
                throw new ProxyException("cannot connect to the proxy: " + e.getMessage());
            }
            if(proxy.type == Type.HTTP)
                httpConnect(socket, proxy, targetHost, targetPort);
            else
                socks5Connect(socket, proxy, targetHost, targetPort);
            ok = true;
            return socket;
        }
        finally
        {
            if(!ok)
                closeQuietly(socket);
        }
    }

    /**
     * 在建好的连接上套 TLS：SNI、证书链（factory 的信任库）、主机名都校验（{@code HTTPS} 端点识别），握手完才返回。
     * factory 为 null 时用和直连（HttpsURLConnection）一样的默认工厂。
     */
    public static SSLSocket tls(Socket tunnel, String host, int port, @Nullable SSLSocketFactory factory) throws IOException
    {
        SSLSocketFactory f = factory != null ? factory : javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLSocket ssl = (SSLSocket) f.createSocket(tunnel, host, port, true);
        try
        {
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            if(!isIpLiteral(host))
                params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            return ssl;
        }
        catch(IOException | RuntimeException e)
        {
            closeQuietly(ssl);
            throw e;
        }
    }

    // ================= HTTP CONNECT =================

    static void httpConnect(Socket socket, Settings proxy, String host, int port) throws IOException
    {
        String authority = hostForAuthority(host) + ":" + port;
        StringBuilder req = new StringBuilder();
        req.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(authority).append("\r\n");
        req.append("User-Agent: ").append(TvHttp.USER_AGENT).append("\r\n");
        if(proxy.hasCredentials())
            req.append("Proxy-Authorization: ").append(basic(proxy.username, proxy.password)).append("\r\n");
        req.append("Proxy-Connection: Keep-Alive\r\n\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        // 逐字节读到空行为止，不能多读（后面就是隧道里的字节）
        InputStream in = socket.getInputStream();
        String status = readLine(in, MAX_REPLY_HEAD);
        int code = statusCode(status);
        int total = status.length();
        while(true)
        {
            String line = readLine(in, MAX_REPLY_HEAD);
            total += line.length() + 2;
            if(total > MAX_REPLY_HEAD)
                throw new ProxyException("HTTP proxy reply too long");
            if(line.isEmpty())
                break;
        }
        if(code == 407)
            throw new AuthException(proxy.hasCredentials() ? "HTTP proxy rejected the username or password (407)" : "HTTP proxy requires a username and password (407)");
        if(code < 200 || code > 299)
            throw new ProxyException("HTTP proxy refused CONNECT (" + code + ")");
    }

    static String basic(String username, String password)
    {
        byte[] raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(raw);
    }

    private static int statusCode(String statusLine) throws ProxyException
    {
        // HTTP/1.1 200 Connection established
        String[] parts = statusLine.split(" ", 3);
        if(parts.length < 2 || !parts[0].startsWith("HTTP/1."))
            throw new ProxyException("not an HTTP proxy reply");
        try
        {
            return Integer.parseInt(parts[1].trim());
        }
        catch(NumberFormatException e)
        {
            throw new ProxyException("not an HTTP proxy reply");
        }
    }

    // ================= SOCKS5（RFC 1928 / RFC 1929） =================

    static void socks5Connect(Socket socket, Settings proxy, String host, int port) throws IOException
    {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        boolean auth = proxy.hasCredentials();
        // 问候：有账号时提供“用户名 / 密码”和“不认证”两种，没账号只提供“不认证”
        if(auth)
            out.write(new byte[]{5, 2, 2, 0});
        else
            out.write(new byte[]{5, 1, 0});
        out.flush();
        int ver = readByte(in);
        int method = readByte(in);
        if(ver != 5)
            throw new ProxyException("not a SOCKS5 proxy");
        if(method == 0xFF)
        {
            if(auth)
                throw new ProxyException("SOCKS5 proxy accepts none of our authentication methods");
            throw new AuthException("SOCKS5 proxy requires a username and password");
        }
        if(method == 2)
        {
            if(!auth)
                throw new ProxyException("SOCKS5 proxy chose an authentication method we did not offer");
            byte[] user = proxy.username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = proxy.password.getBytes(StandardCharsets.UTF_8);
            if(user.length > 255 || pass.length > 255)
                throw new AuthException("SOCKS5 username or password longer than 255 bytes");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(1);
            b.write(user.length);
            b.write(user, 0, user.length);
            b.write(pass.length);
            b.write(pass, 0, pass.length);
            out.write(b.toByteArray());
            out.flush();
            readByte(in);
            int status = readByte(in);
            if(status != 0)
                throw new AuthException("SOCKS5 proxy rejected the username or password");
        }
        else if(method != 0)
        {
            throw new ProxyException("SOCKS5 proxy chose an authentication method we did not offer");
        }
        // CONNECT
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(new byte[]{5, 1, 0}, 0, 3);
        byte[] ip = ipLiteral(host);
        if(ip != null)
        {
            req.write(ip.length == 4 ? 1 : 4);
            req.write(ip, 0, ip.length);
        }
        else
        {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            if(name.length == 0 || name.length > 255)
                throw new ProxyException("host name too long for SOCKS5");
            req.write(3);
            req.write(name.length);
            req.write(name, 0, name.length);
        }
        req.write((port >> 8) & 0xFF);
        req.write(port & 0xFF);
        out.write(req.toByteArray());
        out.flush();
        if(readByte(in) != 5)
            throw new ProxyException("not a SOCKS5 proxy");
        int rep = readByte(in);
        readByte(in);
        int atyp = readByte(in);
        int skip;
        if(atyp == 1)
            skip = 4;
        else if(atyp == 4)
            skip = 16;
        else if(atyp == 3)
            skip = readByte(in);
        else
            throw new ProxyException("bad SOCKS5 reply");
        for(int i = 0; i < skip + 2; i++)
            readByte(in);
        if(rep != 0)
            throw new ProxyException("SOCKS5 proxy could not connect: " + socksReply(rep));
    }

    static String socksReply(int rep)
    {
        switch(rep)
        {
            case 1:
                return "general failure";
            case 2:
                return "not allowed by ruleset";
            case 3:
                return "network unreachable";
            case 4:
                return "host unreachable";
            case 5:
                return "connection refused";
            case 6:
                return "TTL expired";
            case 7:
                return "command not supported";
            case 8:
                return "address type not supported";
            default:
                return "error " + rep;
        }
    }

    // ================= 工具 =================

    private static int readByte(InputStream in) throws IOException
    {
        int b = in.read();
        if(b < 0)
            throw new EOFException("proxy closed the connection");
        return b;
    }

    /** 读一行（以 CRLF 或 LF 结尾，不含换行），超过 max 抛错 */
    static String readLine(InputStream in, int max) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        while(true)
        {
            int b = in.read();
            if(b < 0)
            {
                if(sb.length() == 0)
                    throw new EOFException("connection closed");
                break;
            }
            if(b == '\n')
                break;
            if(b != '\r')
                sb.append((char) b);
            if(sb.length() > max)
                throw new IOException("line too long");
        }
        return sb.toString();
    }

    private static boolean isIpLiteral(String host)
    {
        return ipLiteral(host) != null;
    }

    /** IP 地址的写法（不查 DNS）；不是 IP 地址返回 null */
    @Nullable
    static byte[] ipLiteral(String host)
    {
        String h = host;
        if(h.startsWith("[") && h.endsWith("]"))
            h = h.substring(1, h.length() - 1);
        if(h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || (h.contains(":") && h.matches("[0-9A-Fa-f:.]+")))
        {
            try
            {
                // 纯数字写法不会去查 DNS
                InetAddress a = InetAddress.getByName(h);
                if(a instanceof Inet4Address || a instanceof Inet6Address)
                    return a.getAddress();
            }
            catch(IOException e)
            {
                return null;
            }
        }
        return null;
    }

    private static String hostForAuthority(String host)
    {
        if(host.contains(":") && !host.startsWith("["))
            return "[" + host + "]";
        return host.toLowerCase(Locale.ROOT);
    }

    static void closeQuietly(@Nullable Socket s)
    {
        if(s == null)
            return;
        try
        {
            s.close();
        }
        catch(IOException ignored)
        {
        }
    }
}
