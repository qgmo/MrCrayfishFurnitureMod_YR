package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 白名单、本地库清单、转发的范围判断、cookie 头（都是纯逻辑） */
class TvClientNetTest
{
    private static boolean ok(String url, HostPolicy.Purpose p)
    {
        return HostPolicy.allowed(URI.create(url), p, false);
    }

    @Test
    void hostPolicy()
    {
        assertTrue(ok("https://api.bilibili.com/x/web-interface/view?bvid=x", HostPolicy.Purpose.API));
        assertFalse(ok("http://api.bilibili.com/x", HostPolicy.Purpose.API));
        assertFalse(ok("https://api.bilibili.com.evil.cn/x", HostPolicy.Purpose.API));
        assertFalse(ok("https://user@api.bilibili.com/x", HostPolicy.Purpose.API));
        assertTrue(ok("https://upos-sz-mirrorcos.bilivideo.com/a.m4s", HostPolicy.Purpose.CDN));
        assertTrue(ok("https://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/a.m4s", HostPolicy.Purpose.CDN));
        assertTrue(ok("https://upos-hz-mirrorakam.akamaized.net/a.m4s", HostPolicy.Purpose.CDN));
        assertFalse(ok("https://evil.akamaized.net/a.m4s", HostPolicy.Purpose.CDN));
        assertFalse(ok("https://bilivideo.com.evil.com/a", HostPolicy.Purpose.CDN));
        assertFalse(ok("https://127.0.0.1/a", HostPolicy.Purpose.CDN));
        assertFalse(ok("https://api.bilibili.com/x", HostPolicy.Purpose.CDN));
        assertTrue(ok("https://i0.hdslb.com/bfs/archive/a.jpg", HostPolicy.Purpose.COVER));
        assertTrue(ok("https://dl-yr.qg.mo.cn/maven/a.jar", HostPolicy.Purpose.LIBRARY));
        assertTrue(ok("https://repo1.maven.org/maven2/a.jar", HostPolicy.Purpose.LIBRARY));
        assertFalse(ok("https://repo1.maven.org.evil/maven2/a.jar", HostPolicy.Purpose.LIBRARY));
        assertFalse(ok("http://127.0.0.1:8000/a", HostPolicy.Purpose.API));
        assertTrue(HostPolicy.allowed(URI.create("http://127.0.0.1:8000/a"), HostPolicy.Purpose.API, true));
        assertFalse(HostPolicy.allowed(URI.create("http://localhost:8000/a"), HostPolicy.Purpose.API, true));
        assertEquals("https://a.bilivideo.com/x?y=1&z=%2F", HostPolicy.upgrade("http://a.bilivideo.com/x?y=1&z=%2F").toString());
    }

    @Test
    void platforms()
    {
        assertEquals("windows-x86_64", NativeLibs.platform("Windows 10", "amd64"));
        assertEquals("linux-x86_64", NativeLibs.platform("Linux", "amd64"));
        assertEquals("linux-arm64", NativeLibs.platform("Linux", "aarch64"));
        assertEquals("macosx-x86_64", NativeLibs.platform("Mac OS X", "x86_64"));
        assertEquals("macosx-arm64", NativeLibs.platform("Mac OS X", "aarch64"));
        assertNull(NativeLibs.platform("Windows 7", "x86"));
        assertNull(NativeLibs.platform("FreeBSD", "amd64"));
        assertNull(NativeLibs.platform(null, "amd64"));
    }

    @Test
    void libraryTable()
    {
        assertEquals(5, NativeLibs.FILES.size());
        for(List<NativeLibs.LibFile> files : NativeLibs.FILES.values())
        {
            assertEquals(2, files.size());
            for(NativeLibs.LibFile f : files)
            {
                assertTrue(f.sha256.matches("[0-9a-f]{64}"), f.path);
                assertTrue(f.size > 10_000, f.path);
                assertTrue(f.path.startsWith("org/bytedeco/"), f.path);
                for(String source : NativeLibs.SOURCES)
                    assertTrue(HostPolicy.allowed(URI.create(source + f.path), HostPolicy.Purpose.LIBRARY, false), source);
            }
        }
        assertTrue(NativeLibs.SOURCES.get(0).startsWith("https://dl-yr.qg.mo.cn/"));
    }

    @Test
    void relayRange()
    {
        assertTrue(LocalRelay.exactRange("bytes 0-99/1000", 100));
        assertTrue(LocalRelay.exactRange("bytes 900-999/*", 100));
        assertFalse(LocalRelay.exactRange("bytes 0-99/1000", 99));
        assertFalse(LocalRelay.exactRange("bytes 10-5/1000", 0));
        assertFalse(LocalRelay.exactRange("items 0-99/1000", 100));
    }

    @Test
    void cookieHeader()
    {
        assertEquals("SESSDATA=abc; x=1", TvCookies.sanitize("  Cookie: SESSDATA=abc; x=1  "));
        assertEquals("a=1X-Evil: 1", TvCookies.sanitize("a=1\r\nX-Evil: 1"));
        assertTrue(TvCookies.contains("a=1; BUVID3=x", "buvid3"));
        assertFalse(TvCookies.contains("a=1; xbuvid3=x", "buvid3"));
        assertEquals("", TvCookies.header(""));
    }
}
