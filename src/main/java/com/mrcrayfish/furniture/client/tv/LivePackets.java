package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 直播弹幕连接里的数据包（纯逻辑，悠然服务器魔改，服主 11-18）。包格式参考 PiliPlus（GPL-3.0，只借鉴思路）的
 * {@code lib/tcp/live.dart}，11-18 用脚本连真的弹幕服务器核对过：
 * <ul>
 * <li>每个包 16 字节头（大端）：总长度 4、头长度 2（=16）、协议版本 2、操作码 4、序号 4，后面是包体；一条 WebSocket 消息里可以有几个包首尾相接；</li>
 * <li>协议版本 0 / 1：包体是 JSON（或心跳回应的人气值）；2：包体是 zlib 压缩的若干个包；3：brotli 压缩（Java 自带的库解不了）。
 * 认证时请求 {@code protover: 2}（PiliPlus 用的是 3），服务器照样给 zlib 压缩的包（实测）；万一收到 3 就丢掉；</li>
 * <li>操作码：2 心跳、3 心跳回应、5 消息、7 认证、8 认证回应。</li>
 * </ul>
 * 只关心三种消息：{@code DANMU_MSG}（弹幕，只取文字、模式、颜色）、{@code LIVE}（开播）、{@code PREPARING}（下播），其余（礼物、进场等）不管。
 */
final class LivePackets
{
    static final int OP_HEARTBEAT = 2;
    static final int OP_HEARTBEAT_REPLY = 3;
    static final int OP_MESSAGE = 5;
    static final int OP_AUTH = 7;
    static final int OP_AUTH_REPLY = 8;

    static final int HEADER = 16;
    /** 一条消息解压后最多多大 */
    static final int MAX_INFLATED = 4 * 1024 * 1024;

    static final class Packet
    {
        final int version;
        final int op;
        final byte[] body;

        Packet(int version, int op, byte[] body)
        {
            this.version = version;
            this.op = op;
            this.body = body;
        }

        String text()
        {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** 收到的、我们关心的一条消息 */
    static final class Event
    {
        enum Type
        {
            COMMENT, LIVE, PREPARING
        }

        final Type type;
        @Nullable
        final Danmaku.Comment comment;

        Event(Type type, @Nullable Danmaku.Comment comment)
        {
            this.type = type;
            this.comment = comment;
        }
    }

    private LivePackets() {}

    /** 编一个包（协议版本 1） */
    static byte[] encode(int op, int seq, byte[] body)
    {
        ByteBuffer b = ByteBuffer.allocate(HEADER + body.length);
        b.putInt(HEADER + body.length);
        b.putShort((short) HEADER);
        b.putShort((short) 1);
        b.putInt(op);
        b.putInt(seq);
        b.put(body);
        return b.array();
    }

    /** 认证包的包体：匿名（uid 0），请求 zlib 压缩（protover 2） */
    static byte[] authBody(long roomId, String buvid, String token)
    {
        JsonObject o = new JsonObject();
        o.addProperty("uid", 0);
        o.addProperty("roomid", roomId);
        o.addProperty("protover", 2);
        if(!buvid.isEmpty())
            o.addProperty("buvid", buvid);
        o.addProperty("platform", "web");
        o.addProperty("type", 2);
        o.addProperty("key", token);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 拆一条 WebSocket 消息里的包；zlib 压缩的解开再拆（只解一层）。格式不对抛 IOException */
    static List<Packet> decode(byte[] data) throws IOException
    {
        List<Packet> out = new ArrayList<>();
        decode(data, out, 0);
        return out;
    }

    private static void decode(byte[] data, List<Packet> out, int depth) throws IOException
    {
        int pos = 0;
        while(pos < data.length)
        {
            if(data.length - pos < HEADER)
                throw new IOException("short live packet");
            ByteBuffer b = ByteBuffer.wrap(data, pos, HEADER);
            long total = b.getInt() & 0xFFFFFFFFL;
            int headerLen = b.getShort() & 0xFFFF;
            int version = b.getShort() & 0xFFFF;
            int op = b.getInt();
            if(headerLen < HEADER || total < headerLen || total > data.length - pos)
                throw new IOException("bad live packet length");
            byte[] body = new byte[(int) total - headerLen];
            System.arraycopy(data, pos + headerLen, body, 0, body.length);
            pos += (int) total;
            if(version == 2)
            {
                if(depth > 0)
                    throw new IOException("nested compressed live packets");
                decode(inflate(body), out, depth + 1);
            }
            else if(version == 0 || version == 1)
            {
                out.add(new Packet(version, op, body));
            }
            // 3（brotli）等：解不了，丢掉
        }
    }

    static byte[] inflate(byte[] data) throws IOException
    {
        Inflater inflater = new Inflater();
        try
        {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[16 * 1024];
            while(!inflater.finished())
            {
                int n = inflater.inflate(buf);
                if(n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                    throw new IOException("truncated zlib data");
                out.write(buf, 0, n);
                if(out.size() > MAX_INFLATED)
                    throw new IOException("live packet too large");
            }
            return out.toByteArray();
        }
        catch(DataFormatException e)
        {
            throw new IOException("bad zlib data");
        }
        finally
        {
            inflater.end();
        }
    }

    /** 一条 JSON 消息：弹幕、开播、下播之一，其余返回 null */
    @Nullable
    static Event parseMessage(String json)
    {
        JsonObject o;
        try
        {
            JsonElement e = new JsonParser().parse(json);
            if(!e.isJsonObject())
                return null;
            o = e.getAsJsonObject();
        }
        catch(RuntimeException e)
        {
            return null;
        }
        String cmd = BiliData.getString(o, "cmd", "");
        // 有的版本带后缀，例如 "DANMU_MSG:4:0:2:2:2:0"
        int colon = cmd.indexOf(':');
        if(colon >= 0)
            cmd = cmd.substring(0, colon);
        switch(cmd)
        {
            case "LIVE":
                return new Event(Event.Type.LIVE, null);
            case "PREPARING":
                return new Event(Event.Type.PREPARING, null);
            case "DANMU_MSG":
                Danmaku.Comment c = parseDanmu(o);
                return c == null ? null : new Event(Event.Type.COMMENT, c);
            default:
                return null;
        }
    }

    /** DANMU_MSG：info[1] 是文字，info[0][1] 是模式，info[0][3] 是颜色；时间先填 0（显示时按收到时的位置补上） */
    @Nullable
    static Danmaku.Comment parseDanmu(JsonObject o)
    {
        JsonArray info = BiliData.getArray(o, "info");
        if(info == null || info.size() < 2 || !info.get(1).isJsonPrimitive())
            return null;
        String text = Danmaku.clean(info.get(1).getAsString());
        if(text.isEmpty())
            return null;
        int mode = Danmaku.MODE_SCROLL;
        int color = 0xFFFFFF;
        if(info.get(0).isJsonArray())
        {
            JsonArray meta = info.get(0).getAsJsonArray();
            try
            {
                if(meta.size() > 1 && meta.get(1).isJsonPrimitive())
                {
                    int m = meta.get(1).getAsInt();
                    mode = m == Danmaku.MODE_TOP || m == Danmaku.MODE_BOTTOM ? m : Danmaku.MODE_SCROLL;
                }
                if(meta.size() > 3 && meta.get(3).isJsonPrimitive())
                    color = (int) (meta.get(3).getAsLong() & 0xFFFFFF);
            }
            catch(NumberFormatException | UnsupportedOperationException e)
            {
                // 用默认的
            }
        }
        return new Danmaku.Comment(0, mode, color, text);
    }
}
