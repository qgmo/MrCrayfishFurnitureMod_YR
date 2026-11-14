package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.tls.CertificateInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class PageSnapshotTest
{
    @Test
    void roundTrip() throws IOException
    {
        CertificateInfo leaf = new CertificateInfo("CN=minebay.yr", "CN=中间 CA", new Date(1000), new Date(2000), "AB:CD", Arrays.asList("DNS:minebay.yr"));
        PageSnapshot s = new PageSnapshot("https://minebay.yr/", "<html><body>你好</body></html>".getBytes(StandardCharsets.UTF_8),
                new PageSnapshot.Connection("TLSv1.3", "TLS_AES_128_GCM_SHA256", "fd00::2", 443, Collections.singletonList(leaf)));
        PageSnapshot d = PageSnapshot.decode(s.encode());
        assertEquals(s.url, d.url);
        assertArrayEquals(s.html, d.html);
        assertTrue(d.isSecure());
        assertEquals("TLSv1.3", d.connection.protocol);
        assertEquals(443, d.connection.remotePort);
        assertEquals("CN=中间 CA", d.connection.chain.get(0).issuer);
        assertEquals(2000, d.connection.chain.get(0).notAfter.getTime());
        assertEquals(PageParser.Mode.XHTML, d.mode);
        PageSnapshot plain = PageSnapshot.decode(new PageSnapshot("http://test.yr/", new byte[0], PageParser.Mode.HTML, null).encode());
        assertFalse(plain.isSecure());
        assertEquals(PageParser.Mode.HTML, plain.mode, "解析方式跟着页面走，服务端与旁观者照它解析");
    }

    @Test
    void rejectsBombsAndGarbage()
    {
        // 解压后超过上限：一大块 0 压得很小
        byte[] zeros = new byte[PageSnapshot.MAX_RAW + 1];
        Deflater d = new Deflater();
        d.setInput(zeros);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while(!d.finished())
            out.write(buf, 0, d.deflate(buf));
        assertTrue(out.size() < 1024);
        assertThrows(IOException.class, () -> PageSnapshot.decode(out.toByteArray()));
        assertThrows(IOException.class, () -> PageSnapshot.decode(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, () -> PageSnapshot.decode(new byte[PageSnapshot.MAX_COMPRESSED + 1]));
        // 页面本身超过 64 KiB
        assertThrows(IOException.class, () -> PageSnapshot.decode(new PageSnapshot("u", new byte[PageLimits.MAX_BYTES + 1], null).encode()));
    }
}
