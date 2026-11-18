package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** HLS 直播：列表解析与分片接成一条流（假的取数、假的等待，不联网、不真的等） */
class HlsFeedTest
{
    private static final URI BASE = URI.create("https://d1--ov-gotcha105.bilivideo.com/live-bvc/887870/live_x_2500.m3u8?expires=1");

    private static String list(long first, int count, boolean end)
    {
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:" + first + "\n\n");
        for(int i = 0; i < count; i++)
            sb.append("#EXTINF:3.989,\nlive_x_2500/").append(first + i).append(".ts?sign=s\n");
        if(end)
            sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    @Test
    void parsesSegmentsRelativeToPlaylist() throws IOException
    {
        HlsPlaylist p = HlsPlaylist.parse(list(100, 3, false), BASE);
        assertEquals(4000, p.targetDurationMs);
        assertFalse(p.ended);
        assertEquals(3, p.segments.size());
        assertEquals(100, p.segments.get(0).seq);
        assertEquals(3989, p.segments.get(0).durationMs);
        assertEquals("https://d1--ov-gotcha105.bilivideo.com/live-bvc/887870/live_x_2500/102.ts?sign=s", p.segments.get(2).uri.toString());
    }

    @Test
    void rejectsWhatWeCannotPlay()
    {
        assertThrows(IOException.class, () -> HlsPlaylist.parse("<html>", BASE));
        assertThrows(IOException.class, () -> HlsPlaylist.parse("#EXTM3U\n#EXT-X-MAP:URI=\"h.m4s\"\n#EXTINF:1,\na.m4s\n", BASE));
        assertThrows(IOException.class, () -> HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:1,\na.ts\n", BASE));
    }

    @Test
    void masterPlaylistTakesFirstVariant() throws IOException
    {
        HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nhigh/index.m3u8\n", BASE);
        assertEquals("https://d1--ov-gotcha105.bilivideo.com/live-bvc/887870/low/index.m3u8", p.variant.toString());
    }

    /** 每次取列表按顺序给出预先准备的一份；分片内容就是它的序号 */
    private static final class FakeFetcher implements HlsFeed.Fetcher
    {
        final Deque<String> lists = new ArrayDeque<>();
        final List<String> fetched = new ArrayList<>();

        @Override
        public String playlist(URI uri)
        {
            return lists.size() > 1 ? lists.pollFirst() : lists.peekFirst();
        }

        @Override
        public InputStream segment(URI uri)
        {
            String path = uri.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1).replace(".ts", "");
            fetched.add(name);
            return new ByteArrayInputStream((name + ";").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static String readAll(InputStream in) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[7];
        int n;
        while((n = in.read(buf, 0, buf.length)) > 0)
            out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }

    @Test
    void startsNearTheLiveEdgeAndTakesEachSegmentOnce() throws IOException
    {
        FakeFetcher f = new FakeFetcher();
        // 第一次：10 个分片，从倒数第 3 个开始；之后列表往前滚，新的接着取；最后结束
        f.lists.add(list(1, 10, false));
        f.lists.add(list(3, 10, false));
        f.lists.add(list(3, 10, false));
        f.lists.add(list(5, 10, true));
        List<Long> sleeps = new ArrayList<>();
        HlsFeed feed = new HlsFeed(BASE, f, sleeps::add);
        assertEquals("8;9;10;11;12;13;14;", readAll(feed));
        assertEquals(Arrays.asList("8", "9", "10", "11", "12", "13", "14"), f.fetched);
        // 列表没变的那一次等了半个目标时长
        assertEquals(Arrays.asList(2000L), sleeps);
    }

    @Test
    void stopsWhenNothingNewForTooLong() throws IOException
    {
        FakeFetcher f = new FakeFetcher();
        f.lists.add(list(1, 3, false));
        List<Long> sleeps = new ArrayList<>();
        HlsFeed feed = new HlsFeed(BASE, f, sleeps::add);
        // 取完 3 个以后一直没有新的：等到超过上限就读到结尾（交回去重新查开播状态）
        assertEquals("1;2;3;", readAll(feed));
        long total = 0;
        for(long s : sleeps)
            total += s;
        assertTrue(total <= HlsFeed.MAX_IDLE_MS && total + 2000 > HlsFeed.MAX_IDLE_MS, "waited " + total);
        assertEquals(-1, feed.read());
    }

    @Test
    void triesTheNextAddressWhenThePlaylistDoesNotAnswer() throws IOException
    {
        URI dead = URI.create("https://d1--ov-gotcha105.bilivideo.com/live-bvc/1/x.m3u8?sign=a");
        URI alive = URI.create("https://d1--ov-gotcha105b.bilivideo.com/live-bvc/1/x.m3u8?sign=b");
        List<URI> asked = new ArrayList<>();
        FakeFetcher ok = new FakeFetcher();
        ok.lists.add(list(1, 2, true));
        HlsFeed.Fetcher f = new HlsFeed.Fetcher()
        {
            @Override
            public String playlist(URI uri) throws IOException
            {
                asked.add(uri);
                if(uri.equals(dead))
                    throw new java.net.SocketTimeoutException("Read timed out");
                return ok.playlist(uri);
            }

            @Override
            public InputStream segment(URI uri)
            {
                return ok.segment(uri);
            }
        };
        HlsFeed feed = new HlsFeed(Arrays.asList(dead, alive), f, ms -> {});
        assertEquals("1;2;", readAll(feed));
        assertEquals(Arrays.asList(dead, alive, alive), asked);
        // 只有一个地址又取不到：读的时候报错（交回去重新取流）
        HlsFeed only = new HlsFeed(dead, f, ms -> {});
        assertThrows(IOException.class, only::read);
    }
}
