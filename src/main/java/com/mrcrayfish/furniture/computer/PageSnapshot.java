package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.tls.CertificateInfo;
import moe.qingu.youran.api.network.tls.TlsInfo;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 操作者当前看的页面（给旁观者看）：网址、页面原文与解析方式、是不是 https 与连接信息。操作者的客户端上传给服务端，
 * 服务端存在电脑方块实体里（只在内存）并转给旁观者，旁观者本地自己渲染。
 * <p>
 * 编码：DataOutput 写字段，再整体 deflate。解码时先限制解压后的大小（防压缩炸弹），再逐个字段检查长度。
 * 连接信息是操作者的客户端说的，旁观者照着显示（服务端不核实）。
 */
public final class PageSnapshot
{
    /** 网址等短字符串的上限（字符） */
    static final int MAX_TEXT = 2048;
    static final int MAX_CHAIN = 6;
    static final int MAX_SANS = 32;
    /** 解压后最多这么多字节：页面 + 元数据 */
    public static final int MAX_RAW = PageLimits.MAX_BYTES + 48 * 1024;
    /** 压缩后最多这么多字节（上传、同步时先看它） */
    public static final int MAX_COMPRESSED = MAX_RAW + 1024;
    static final int VERSION = 2;

    public final String url;
    public final byte[] html;
    /** 按 HTML 还是 XHTML 解析（操作者按响应的 Content-Type 定；服务端、旁观者照这个解析，数出来的元素与层数才一样） */
    public final PageParser.Mode mode;
    @Nullable
    public final Connection connection;

    /** https 的连接信息（给“连接信息”弹窗与锁用） */
    public static final class Connection
    {
        public final String protocol;
        public final String cipherSuite;
        public final String remoteAddress;
        public final int remotePort;
        public final List<CertificateInfo> chain;

        public Connection(String protocol, String cipherSuite, String remoteAddress, int remotePort, List<CertificateInfo> chain)
        {
            this.protocol = protocol;
            this.cipherSuite = cipherSuite;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        }

        public static Connection of(TlsInfo tls)
        {
            return new Connection(tls.protocol, tls.cipherSuite, String.valueOf(tls.remoteAddress), tls.remotePort, CertificateInfo.of(tls.peerChain));
        }
    }

    public PageSnapshot(String url, byte[] html, @Nullable Connection connection)
    {
        this(url, html, PageParser.Mode.XHTML, connection);
    }

    public PageSnapshot(String url, byte[] html, PageParser.Mode mode, @Nullable Connection connection)
    {
        this.url = url;
        this.html = html;
        this.mode = mode;
        this.connection = connection;
    }

    public boolean isSecure()
    {
        return connection != null;
    }

    public byte[] encode()
    {
        try
        {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(VERSION);
            writeText(out, url);
            out.writeInt(html.length);
            out.write(html);
            out.writeByte(mode.ordinal());
            out.writeBoolean(connection != null);
            if(connection != null)
            {
                writeText(out, connection.protocol);
                writeText(out, connection.cipherSuite);
                writeText(out, connection.remoteAddress);
                out.writeShort(connection.remotePort);
                out.writeByte(Math.min(MAX_CHAIN, connection.chain.size()));
                for(int i = 0; i < Math.min(MAX_CHAIN, connection.chain.size()); i++)
                {
                    CertificateInfo c = connection.chain.get(i);
                    writeText(out, c.subject);
                    writeText(out, c.issuer);
                    out.writeLong(c.notBefore.getTime());
                    out.writeLong(c.notAfter.getTime());
                    writeText(out, c.sha256);
                    out.writeByte(Math.min(MAX_SANS, c.subjectAltNames.size()));
                    for(int j = 0; j < Math.min(MAX_SANS, c.subjectAltNames.size()); j++)
                        writeText(out, c.subjectAltNames.get(j));
                }
            }
            out.flush();
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
            try
            {
                d.setInput(raw.toByteArray());
                d.finish();
                ByteArrayOutputStream z = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while(!d.finished())
                {
                    int n = d.deflate(buf);
                    z.write(buf, 0, n);
                }
                return z.toByteArray();
            }
            finally
            {
                d.end();
            }
        }
        catch(IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** 解码；不合格（太大、格式不对）时抛出 IOException */
    public static PageSnapshot decode(byte[] compressed) throws IOException
    {
        if(compressed.length > MAX_COMPRESSED)
            throw new IOException("压缩后太大");
        byte[] raw = inflate(compressed, MAX_RAW);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        if(in.readUnsignedByte() != VERSION)
            throw new IOException("版本不对");
        String url = readText(in);
        int len = in.readInt();
        if(len < 0 || len > PageLimits.MAX_BYTES)
            throw new IOException("页面太大");
        byte[] html = new byte[len];
        in.readFully(html);
        int modeIndex = in.readUnsignedByte();
        if(modeIndex >= PageParser.Mode.values().length)
            throw new IOException("解析方式不对");
        PageParser.Mode mode = PageParser.Mode.values()[modeIndex];
        Connection conn = null;
        if(in.readBoolean())
        {
            String protocol = readText(in);
            String cipher = readText(in);
            String remote = readText(in);
            int port = in.readUnsignedShort();
            int n = in.readUnsignedByte();
            if(n > MAX_CHAIN)
                throw new IOException("证书链太长");
            List<CertificateInfo> chain = new ArrayList<>();
            for(int i = 0; i < n; i++)
            {
                String subject = readText(in);
                String issuer = readText(in);
                Date from = new Date(in.readLong());
                Date to = new Date(in.readLong());
                String sha = readText(in);
                int m = in.readUnsignedByte();
                if(m > MAX_SANS)
                    throw new IOException("名字太多");
                List<String> sans = new ArrayList<>();
                for(int j = 0; j < m; j++)
                    sans.add(readText(in));
                chain.add(new CertificateInfo(subject, issuer, from, to, sha, sans));
            }
            conn = new Connection(protocol, cipher, remote, port, chain);
        }
        if(in.available() > 0)
            throw new IOException("多出来的数据");
        return new PageSnapshot(url, html, mode, conn);
    }

    static byte[] inflate(byte[] compressed, int max) throws IOException
    {
        Inflater inf = new Inflater();
        try
        {
            inf.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while(!inf.finished())
            {
                int n;
                try
                {
                    n = inf.inflate(buf);
                }
                catch(DataFormatException e)
                {
                    throw new IOException("压缩数据不对");
                }
                if(n == 0 && (inf.needsInput() || inf.needsDictionary()))
                    throw new IOException("压缩数据不完整");
                out.write(buf, 0, n);
                if(out.size() > max)
                    throw new IOException("解压后太大");
            }
            return out.toByteArray();
        }
        finally
        {
            inf.end();
        }
    }

    private static void writeText(DataOutputStream out, String s) throws IOException
    {
        String t = s == null ? "" : (s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s);
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }

    private static String readText(DataInputStream in) throws IOException
    {
        int len = in.readUnsignedShort();
        if(len > MAX_TEXT * 4)
            throw new IOException("字符串太长");
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
