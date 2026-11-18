package com.mrcrayfish.furniture.client.tv;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/** 直播弹幕：数据包、WebSocket 帧与握手、断线重连的等待（假时钟）、排版 */
class LiveDanmakuTest
{
    private static final String DANMU = "{'cmd':'DANMU_MSG','info':[[0,1,25,16777215,1795000000000,0,0,'x',0,0,0,'',0,'{}','{}',{'mode':0}],'旅游团来了',[1,'某人'],[],{},{}]}".replace('\'', '"');

    private static byte[] zlib(byte[] data)
    {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        byte[] buf = new byte[data.length + 64];
        int n = d.deflate(buf);
        d.end();
        return Arrays.copyOf(buf, n);
    }

    private static byte[] packet(int version, int op, byte[] body)
    {
        ByteBuffer b = ByteBuffer.allocate(16 + body.length);
        b.putInt(16 + body.length).putShort((short) 16).putShort((short) version).putInt(op).putInt(0).put(body);
        return b.array();
    }

    private static byte[] concat(byte[]... parts)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for(byte[] p : parts)
            out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    void encodeHeader() throws IOException
    {
        byte[] auth = LivePackets.encode(LivePackets.OP_AUTH, 1, "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(18, auth.length);
        assertArrayEquals(new byte[]{0, 0, 0, 18, 0, 16, 0, 1, 0, 0, 0, 7, 0, 0, 0, 1, '{', '}'}, auth);
        List<LivePackets.Packet> back = LivePackets.decode(auth);
        assertEquals(1, back.size());
        assertEquals(LivePackets.OP_AUTH, back.get(0).op);
        assertEquals("{}", back.get(0).text());
        // 心跳没有包体
        assertEquals(16, LivePackets.encode(LivePackets.OP_HEARTBEAT, 2, new byte[0]).length);
    }

    @Test
    void authBodyAsksForZlib()
    {
        String body = new String(LivePackets.authBody(26808302, "B3-XYZ", "tok"), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"uid\":0"), body);
        assertTrue(body.contains("\"roomid\":26808302"), body);
        assertTrue(body.contains("\"protover\":2"), body);
        assertTrue(body.contains("\"buvid\":\"B3-XYZ\""), body);
        assertTrue(body.contains("\"key\":\"tok\""), body);
        assertFalse(new String(LivePackets.authBody(1, "", "t"), StandardCharsets.UTF_8).contains("buvid"));
    }

    @Test
    void zlibMessagesAreUnpacked() throws IOException
    {
        byte[] inner = concat(packet(0, 5, DANMU.getBytes(StandardCharsets.UTF_8)), packet(0, 5, "{\"cmd\":\"SEND_GIFT_V2\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)));
        byte[] message = concat(packet(2, 5, zlib(inner)), packet(1, 3, new byte[]{0, 0, 0, 1}));
        List<LivePackets.Packet> list = LivePackets.decode(message);
        assertEquals(3, list.size());
        assertEquals(5, list.get(0).op);
        assertEquals(3, list.get(2).op);
        LivePackets.Event e = LivePackets.parseMessage(list.get(0).text());
        assertNotNull(e);
        assertEquals(LivePackets.Event.Type.COMMENT, e.type);
        assertEquals("旅游团来了", e.comment.text);
        assertEquals(Danmaku.MODE_SCROLL, e.comment.mode);
        assertEquals(0xFFFFFF, e.comment.color);
        // 礼物等不显示
        assertNull(LivePackets.parseMessage(list.get(1).text()));
    }

    @Test
    void brotliAndBrokenPackets() throws IOException
    {
        // 协议 3（brotli）解不了：丢掉，不报错
        assertTrue(LivePackets.decode(packet(3, 5, new byte[]{1, 2, 3})).isEmpty());
        assertThrows(IOException.class, () -> LivePackets.decode(new byte[]{0, 0, 0, 40, 0, 16}));
        byte[] bad = packet(0, 5, new byte[4]);
        bad[3] = (byte) 200;
        assertThrows(IOException.class, () -> LivePackets.decode(bad));
        assertThrows(IOException.class, () -> LivePackets.decode(packet(2, 5, new byte[]{9, 9, 9})));
        // 压缩里再套压缩：不认
        assertThrows(IOException.class, () -> LivePackets.decode(packet(2, 5, zlib(packet(2, 5, zlib(new byte[0]))))));
    }

    @Test
    void liveAndPreparingAndOddMessages()
    {
        assertEquals(LivePackets.Event.Type.LIVE, LivePackets.parseMessage("{\"cmd\":\"LIVE\",\"roomid\":1}").type);
        assertEquals(LivePackets.Event.Type.PREPARING, LivePackets.parseMessage("{\"cmd\":\"PREPARING\",\"roomid\":\"1\"}").type);
        assertEquals(LivePackets.Event.Type.COMMENT, LivePackets.parseMessage(DANMU.replace("\"DANMU_MSG\"", "\"DANMU_MSG:4:0:2:2:2:0\"")).type);
        assertNull(LivePackets.parseMessage("not json"));
        assertNull(LivePackets.parseMessage("{\"cmd\":\"DANMU_MSG\",\"info\":[]}"));
        // 去掉格式符、控制字符；顶部弹幕、颜色
        LivePackets.Event e = LivePackets.parseMessage("{\"cmd\":\"DANMU_MSG\",\"info\":[[0,5,25,16711680],\"§c红\\u0007字\",[1,\"a\"]]}");
        assertEquals("c红字", e.comment.text);
        assertEquals(Danmaku.MODE_TOP, e.comment.mode);
        assertEquals(0xFF0000, e.comment.color);
    }

    @Test
    void websocketAcceptKeyFromTheRfcExample()
    {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", LiveSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void handshakeIsChecked() throws IOException
    {
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        LiveSocket.checkHandshake("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n", key);
        assertThrows(IOException.class, () -> LiveSocket.checkHandshake("HTTP/1.1 403 Forbidden\r\n\r\n", key));
        assertThrows(IOException.class, () -> LiveSocket.checkHandshake("HTTP/1.1 101 OK\r\nSec-WebSocket-Accept: wrong\r\n\r\n", key));
        String head = LiveSocket.readHead(new ByteArrayInputStream("HTTP/1.1 101 OK\r\nA: b\r\n\r\nrest".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("HTTP/1.1 101 OK\r\nA: b\r\n\r\n", head);
        assertTrue(LiveSocket.handshake("broadcastlv.chat.bilibili.com", key).startsWith("GET /sub HTTP/1.1\r\nHost: broadcastlv.chat.bilibili.com\r\n"));
        assertFalse(LiveSocket.handshake("h", key).toLowerCase().contains("cookie"));
    }

    @Test
    void framesRoundTripWithEveryLengthForm() throws IOException
    {
        byte[] mask = {1, 2, 3, 4};
        for(int n : new int[]{0, 5, 125, 126, 300, 65535, 65536, 70000})
        {
            byte[] payload = new byte[n];
            for(int i = 0; i < n; i++)
                payload[i] = (byte) (i * 7);
            byte[] f = LiveSocket.frame(LiveSocket.OP_BINARY, payload, mask);
            // 客户端的帧要加掩码
            assertEquals(0x80, f[1] & 0x80);
            LiveSocket.Frame back = LiveSocket.readFrame(new ByteArrayInputStream(f), 1 << 20);
            assertTrue(back.fin);
            assertEquals(LiveSocket.OP_BINARY, back.opcode);
            assertArrayEquals(payload, back.payload);
        }
        assertThrows(IOException.class, () -> LiveSocket.readFrame(new ByteArrayInputStream(LiveSocket.frame(2, new byte[300], mask)), 100));
        assertThrows(IOException.class, () -> LiveSocket.readFrame(new ByteArrayInputStream(new byte[]{(byte) 0x82, 10, 1}), 100));
    }

    /** 服务器发来的帧（不加掩码） */
    private static byte[] serverFrame(boolean fin, int opcode, byte[] payload)
    {
        byte[] f = new byte[2 + payload.length];
        f[0] = (byte) ((fin ? 0x80 : 0) | opcode);
        f[1] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 2, payload.length);
        return f;
    }

    @Test
    void messagesPingAndClose() throws IOException
    {
        byte[] stream = concat(
                serverFrame(true, LiveSocket.OP_PING, new byte[]{9}),
                serverFrame(false, LiveSocket.OP_BINARY, new byte[]{1, 2}),
                serverFrame(true, LiveSocket.OP_CONTINUATION, new byte[]{3}),
                serverFrame(true, LiveSocket.OP_BINARY, new byte[]{4}),
                serverFrame(true, LiveSocket.OP_CLOSE, new byte[0]));
        ByteArrayInputStream in = new ByteArrayInputStream(stream);
        List<String> replies = new ArrayList<>();
        LiveSocket.Replier r = (op, p) -> replies.add(op + ":" + Arrays.toString(p));
        assertArrayEquals(new byte[]{1, 2, 3}, LiveSocket.readMessage(in, r));
        assertEquals(Arrays.asList(LiveSocket.OP_PONG + ":[9]"), replies);
        assertArrayEquals(new byte[]{4}, LiveSocket.readMessage(in, r));
        assertNull(LiveSocket.readMessage(in, r));
        assertThrows(IOException.class, () -> LiveSocket.readMessage(new ByteArrayInputStream(serverFrame(true, LiveSocket.OP_CONTINUATION, new byte[1])), r));
    }

    @Test
    void backoffDoublesUpToTheCapAndResetsAfterAStableConnection()
    {
        Reconnect r = new Reconnect();
        assertEquals(1_000, r.next(0));
        assertEquals(2_000, r.next(500));
        assertEquals(4_000, r.next(0));
        assertEquals(8_000, r.next(0));
        assertEquals(16_000, r.next(0));
        assertEquals(32_000, r.next(0));
        assertEquals(60_000, r.next(0));
        assertEquals(60_000, r.next(0));
        // 连着撑过一分钟：从头算
        assertEquals(1_000, r.next(Reconnect.STABLE_MS));
        assertEquals(2_000, r.next(10));
    }

    @Test
    void reconnectLoopWithAFakeClock()
    {
        long[] now = {0};
        List<Long> sleeps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int[] attempts = {0};
        // 连不上 3 次，第 4 次连上撑了 2 分钟后断，第 5 次连不上，然后关掉
        Reconnect.Attempt attempt = () ->
        {
            attempts[0]++;
            if(attempts[0] == 4)
            {
                now[0] += 120_000;
                return;
            }
            now[0] += 100;
            throw new IOException("refused " + attempts[0]);
        };
        Reconnect.loop(attempt, () -> attempts[0] >= 5, () -> now[0], ms ->
        {
            sleeps.add(ms);
            now[0] += ms;
        }, e -> errors.add(e.getMessage()));
        assertEquals(5, attempts[0]);
        assertEquals(Arrays.asList(1_000L, 2_000L, 4_000L, 1_000L), sleeps);
        assertEquals(Arrays.asList("refused 1", "refused 2", "refused 3", "refused 5"), errors);
    }

    @Test
    void reconnectStopsWhenInterrupted()
    {
        int[] attempts = {0};
        Reconnect.loop(() -> attempts[0]++, () -> false, () -> 0L, ms ->
        {
            throw new InterruptedException();
        }, e -> {});
        assertEquals(1, attempts[0]);
    }

    @Test
    void liveClockFollowsTheNewestFrames()
    {
        LiveClock c = new LiveClock();
        assertEquals(-1, c.target(1000));
        c.observe(50_000, 1000);
        // 收到第一帧后晚一点点放
        assertEquals(50_000 - LiveClock.DELAY_MS, c.target(1000));
        assertEquals(50_000 - LiveClock.DELAY_MS + 500, c.target(1500));
        // 平稳时队列最前面的帧和时钟差不多：不动
        c.observe(50_300, 1500);
        assertEquals(50_000 - LiveClock.DELAY_MS + 500, c.target(1500));
        // 卡了一阵（时钟走到前面去了）或流重连后时间戳跳了：重新对上
        c.observe(50_400, 6000);
        assertEquals(50_400 - LiveClock.DELAY_MS, c.target(6000));
        c.observe(900_000, 6100);
        assertEquals(900_000 - LiveClock.DELAY_MS, c.target(6100));
        c.reset();
        assertEquals(-1, c.target(7000));
    }

    @Test
    void incrementalLayoutMatchesBatchAndKeepsPositions()
    {
        List<Danmaku.Comment> comments = new ArrayList<>();
        for(int i = 0; i < 60; i++)
            comments.add(new Danmaku.Comment(i * 150L, i % 9 == 0 ? Danmaku.MODE_TOP : Danmaku.MODE_SCROLL, 0xFFFFFF, "弹幕" + i));
        List<Danmaku.Placed> batch = Danmaku.layout(comments, 300, 4, s -> s.length() * 9);
        Danmaku.Incremental inc = new Danmaku.Incremental(300, 4);
        for(Danmaku.Comment c : comments)
            inc.add(c, c.text.length() * 9);
        assertEquals(batch.size(), inc.placed().size());
        for(int i = 0; i < batch.size(); i++)
        {
            assertSame(batch.get(i).comment, inc.placed().get(i).comment);
            assertEquals(batch.get(i).lane, inc.placed().get(i).lane);
        }
        // 去掉已经走完的：剩下的位置不变
        List<Integer> lanesAfter = new ArrayList<>();
        for(Danmaku.Placed p : inc.placed())
            if(p.comment.timeMs >= 4000)
                lanesAfter.add(p.lane);
        inc.trimBefore(4000);
        List<Integer> kept = new ArrayList<>();
        for(Danmaku.Placed p : inc.placed())
            kept.add(p.lane);
        assertEquals(lanesAfter, kept);
        // 时间倒退的按上一条的时间算
        Danmaku.Incremental inc2 = new Danmaku.Incremental(300, 4);
        inc2.add(new Danmaku.Comment(1000, Danmaku.MODE_SCROLL, 0, "a"), 9);
        inc2.add(new Danmaku.Comment(500, Danmaku.MODE_SCROLL, 0, "b"), 9);
        assertEquals(1000, inc2.placed().get(1).comment.timeMs);
    }
}
