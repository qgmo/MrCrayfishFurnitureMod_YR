package com.mrcrayfish.furniture.client.tv;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 直播弹幕用的最小 WebSocket 客户端（悠然服务器魔改，服主 11-18；Java 8 没有自带的 WebSocket，又不想引入大的依赖）。
 * <ul>
 * <li>只连 {@code wss://<弹幕服务器>:443/sub}，主机先过 {@link HostPolicy}（{@link HostPolicy.Purpose#LIVE_CHAT}）；
 * 经代理时同 {@link TvHttp}：自己建隧道（{@link TvProxy}），再套 TLS 并校验证书与主机名；</li>
 * <li>握手核对 {@code 101} 与 {@code Sec-WebSocket-Accept}；不发 Cookie；</li>
 * <li>发出去的帧都加掩码（协议要求）；收到的消息最大 {@value #MAX_MESSAGE} 字节，分片的拼起来，ping 回 pong，close 当连接结束。</li>
 * </ul>
 * 帧的编码、解码与握手的核对是静态方法，单元测试直接用字节流测。
 */
final class LiveSocket implements Closeable
{
    static final int MAX_MESSAGE = 2 * 1024 * 1024;
    private static final int MAX_HEAD = 8192;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final int OP_CONTINUATION = 0;
    static final int OP_TEXT = 1;
    static final int OP_BINARY = 2;
    static final int OP_CLOSE = 8;
    static final int OP_PING = 9;
    static final int OP_PONG = 10;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    LiveSocket(Socket socket, InputStream in, OutputStream out)
    {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /** 连上 wss://host:443/sub 并完成握手。readTimeoutMs 是读超时（服务器每 30 秒回一次心跳，超时就是断了） */
    static LiveSocket connect(String host, int connectTimeoutMs, int readTimeoutMs) throws IOException
    {
        URI uri = URI.create("wss://" + host + ":443/sub");
        if(!HostPolicy.allowedLiveChat(uri))
            throw new IOException("host not allowed: " + host);
        TvProxy.Settings proxy = TvClientConfig.proxy(TvClientConfig.get());
        Socket raw;
        if(proxy != null)
        {
            raw = TvProxy.open(proxy, host, 443, connectTimeoutMs, readTimeoutMs);
        }
        else
        {
            raw = new Socket(Proxy.NO_PROXY);
            try
            {
                raw.setSoTimeout(readTimeoutMs);
                raw.connect(new InetSocketAddress(host, 443), connectTimeoutMs);
            }
            catch(IOException e)
            {
                TvProxy.closeQuietly(raw);
                throw e;
            }
        }
        Socket tls = TvProxy.tls(raw, host, 443, null);
        try
        {
            tls.setSoTimeout(readTimeoutMs);
            InputStream in = new BufferedInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            out.write(handshake(host, key).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            checkHandshake(readHead(in), key);
            return new LiveSocket(tls, in, out);
        }
        catch(IOException | RuntimeException e)
        {
            TvProxy.closeQuietly(tls);
            throw e;
        }
    }

    static String handshake(String host, String key)
    {
        return "GET /sub HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: https://live.bilibili.com\r\n"
                + "User-Agent: " + TvHttp.USER_AGENT + "\r\n\r\n";
    }

    static String acceptFor(String key)
    {
        try
        {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** 核对握手的回应：101 且 Sec-WebSocket-Accept 对得上 */
    static void checkHandshake(String head, String key) throws IOException
    {
        String[] lines = head.split("\r\n");
        if(lines.length == 0 || !lines[0].matches("HTTP/1\\.1 101( .*)?"))
            throw new IOException("live danmaku handshake refused: " + (lines.length == 0 ? "" : lines[0].replaceAll("[^ -~]", "?")));
        String accept = null;
        for(int i = 1; i < lines.length; i++)
        {
            int colon = lines[i].indexOf(':');
            if(colon > 0 && lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("sec-websocket-accept"))
                accept = lines[i].substring(colon + 1).trim();
        }
        if(!acceptFor(key).equals(accept))
            throw new IOException("live danmaku handshake: bad accept key");
    }

    static String readHead(InputStream in) throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while(buf.size() < MAX_HEAD)
        {
            int b = in.read();
            if(b < 0)
                throw new EOFException("live danmaku handshake: connection closed");
            buf.write(b);
            if((state == 0 || state == 2) && b == '\r')
                state++;
            else if((state == 1 || state == 3) && b == '\n')
                state++;
            else
                state = b == '\r' ? 1 : 0;
            if(state == 4)
                return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        throw new IOException("live danmaku handshake: header too long");
    }

    /** 编一个客户端帧（FIN、加掩码） */
    static byte[] frame(int opcode, byte[] payload, byte[] mask)
    {
        int n = payload.length;
        int headLen = n < 126 ? 2 : n <= 0xFFFF ? 4 : 10;
        byte[] f = new byte[headLen + 4 + n];
        f[0] = (byte) (0x80 | opcode);
        if(n < 126)
        {
            f[1] = (byte) (0x80 | n);
        }
        else if(n <= 0xFFFF)
        {
            f[1] = (byte) (0x80 | 126);
            f[2] = (byte) (n >>> 8);
            f[3] = (byte) n;
        }
        else
        {
            f[1] = (byte) (0x80 | 127);
            for(int i = 0; i < 8; i++)
                f[2 + i] = (byte) ((long) n >>> (56 - 8 * i));
        }
        System.arraycopy(mask, 0, f, headLen, 4);
        for(int i = 0; i < n; i++)
            f[headLen + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        return f;
    }

    static final class Frame
    {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload)
        {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** 读一帧（服务器发来的帧不加掩码，加了也照解）；超过 max 字节抛异常 */
    static Frame readFrame(InputStream in, int max) throws IOException
    {
        int b0 = readByte(in), b1 = readByte(in);
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if(len == 126)
            len = (readByte(in) << 8) | readByte(in);
        else if(len == 127)
        {
            len = 0;
            for(int i = 0; i < 8; i++)
                len = (len << 8) | readByte(in);
        }
        if(len < 0 || len > max)
            throw new IOException("live danmaku frame too large");
        byte[] mask = null;
        if(masked)
        {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if(mask != null)
        {
            for(int i = 0; i < payload.length; i++)
                payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, opcode, payload);
    }

    private static int readByte(InputStream in) throws IOException
    {
        int b = in.read();
        if(b < 0)
            throw new EOFException("live danmaku connection closed");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException
    {
        int off = 0;
        while(off < buf.length)
        {
            int n = in.read(buf, off, buf.length - off);
            if(n < 0)
                throw new EOFException("live danmaku connection closed");
            off += n;
        }
    }

    /** 发一条二进制消息（心跳线程和读线程都可能发，加锁） */
    synchronized void sendBinary(byte[] data) throws IOException
    {
        send(OP_BINARY, data);
    }

    private synchronized void send(int opcode, byte[] data) throws IOException
    {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        out.write(frame(opcode, data, mask));
        out.flush();
    }

    /** 读下一条完整的数据消息（二进制或文本）；对方关闭时返回 null */
    @Nullable
    byte[] readMessage() throws IOException
    {
        return readMessage(in, this::reply);
    }

    interface Replier
    {
        void reply(int opcode, byte[] payload) throws IOException;
    }

    private void reply(int opcode, byte[] payload) throws IOException
    {
        send(opcode, payload);
    }

    /** 读一条完整的消息：分片的拼起来，ping 回 pong，close 返回 null */
    @Nullable
    static byte[] readMessage(InputStream in, Replier replier) throws IOException
    {
        ByteArrayOutputStream message = null;
        while(true)
        {
            Frame f = readFrame(in, MAX_MESSAGE);
            switch(f.opcode)
            {
                case OP_PING:
                    replier.reply(OP_PONG, f.payload);
                    continue;
                case OP_PONG:
                    continue;
                case OP_CLOSE:
                    return null;
                case OP_TEXT:
                case OP_BINARY:
                    if(message != null)
                        throw new IOException("live danmaku: unexpected new message");
                    if(f.fin)
                        return f.payload;
                    message = new ByteArrayOutputStream();
                    message.write(f.payload);
                    continue;
                case OP_CONTINUATION:
                    if(message == null)
                        throw new IOException("live danmaku: stray continuation");
                    message.write(f.payload);
                    if(message.size() > MAX_MESSAGE)
                        throw new IOException("live danmaku message too large");
                    if(f.fin)
                        return message.toByteArray();
                    continue;
                default:
                    throw new IOException("live danmaku: unknown opcode " + f.opcode);
            }
        }
    }

    @Override
    public void close()
    {
        TvProxy.closeQuietly(socket);
    }
}
