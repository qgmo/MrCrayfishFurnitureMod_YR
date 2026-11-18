package com.mrcrayfish.furniture.client.tv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * bilibili 直播接口返回的解析（纯逻辑，悠然服务器魔改，服主 11-18）。接口与参数参考 PiliPlus（GPL-3.0，只借鉴思路）
 * 的 {@code lib/http/live.dart}、{@code lib/pages/live_room/controller.dart}，字段 11-18 用 curl 实测核对过：
 * <ul>
 * <li>{@code room/v1/Room/room_init?id=}：短号换真实房间号，{@code live_status} 0 未开播、1 直播中、2 轮播；房间不存在 code 60004；</li>
 * <li>{@code xlive/web-room/v2/index/getRoomPlayInfo}：{@code playurl_info.playurl.stream[]} → {@code format[]} → {@code codec[]}，
 * 每个 codec 有 {@code base_url}、{@code url_info[]{host, extra}}、{@code current_qn}、{@code accept_qn}；地址 = host + base_url + extra；</li>
 * <li>{@code xlive/web-room/v1/index/getDanmuInfo}（要 WBI 签名，不签名返回 -352）：{@code token} 与 {@code host_list[]{host, wss_port}}。</li>
 * </ul>
 * 只放 FFmpeg 现有解码链吃得下、又不用 FFmpeg 自己联网的：HTTP-FLV（一条长连接）与 HLS 的 TS 分片（由 {@link HlsFeed} 在 Java 里取、接起来）；
 * fMP4 分片的 HLS 不用。编码优先 H.264，其次 H.265。
 */
public final class LiveData
{
    /** 直播的清晰度代码（和点播的 qn 不是一套）：流畅、高清、超清、蓝光、原画 */
    public static final int LQN_SMOOTH = 80;
    public static final int LQN_HIGH = 150;
    public static final int LQN_SUPER = 250;
    public static final int LQN_BLURAY = 400;
    public static final int LQN_ORIGINAL = 10000;

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_LIVE = 1;
    /** 轮播（放录像），按没开播算（同 PiliPlus：live_status 不是 1 就是“未开播”） */
    public static final int STATUS_ROUND = 2;

    private LiveData() {}

    /**
     * 点播的清晰度上限（{@link BiliData#QN_480P} 等，大屏以外最高 480P）对应要请求的直播清晰度：480P 及以下请求“流畅”，
     * 720P 请求“高清”，1080P 请求“蓝光”。实际给的可能更高（这一档没有时），解码时照样缩到上限对应的尺寸（{@link VideoDecoder#maxSize}）。
     */
    public static int wantedQn(int vodCap)
    {
        if(vodCap >= BiliData.QN_1080P)
            return LQN_BLURAY;
        if(vodCap >= BiliData.QN_720P)
            return LQN_HIGH;
        return LQN_SMOOTH;
    }

    /** 能给的清晰度里选：不超过 wanted 的最高一档；都超过时选最低的；列表空时就是 wanted */
    public static int pickQn(List<Integer> accept, int wanted)
    {
        int best = -1, lowest = Integer.MAX_VALUE;
        for(int q : accept)
        {
            if(q <= 0)
                continue;
            lowest = Math.min(lowest, q);
            if(q <= wanted && q > best)
                best = q;
        }
        if(best > 0)
            return best;
        return lowest == Integer.MAX_VALUE ? wanted : lowest;
    }

    /** 直播间：真实房间号与开播状态 */
    public static final class Room
    {
        public final long roomId;
        public final int liveStatus;

        public Room(long roomId, int liveStatus)
        {
            this.roomId = roomId;
            this.liveStatus = liveStatus;
        }

        public boolean isLive()
        {
            return liveStatus == STATUS_LIVE;
        }
    }

    /** 一路直播流（某种协议、封装、编码的一档清晰度） */
    public static final class Stream
    {
        public final String protocol;
        public final String format;
        public final String codec;
        public final int qn;
        public final List<Integer> acceptQn;
        /** 按顺序的候选地址（不同 CDN 节点） */
        public final List<URI> urls;

        Stream(String protocol, String format, String codec, int qn, List<Integer> acceptQn, List<URI> urls)
        {
            this.protocol = protocol;
            this.format = format;
            this.codec = codec;
            this.qn = qn;
            this.acceptQn = Collections.unmodifiableList(acceptQn);
            this.urls = Collections.unmodifiableList(urls);
        }

        /** HTTP-FLV：一条长连接 */
        public boolean isFlv()
        {
            return protocol.equals("http_stream") && format.equals("flv");
        }

        /** HLS 的 TS 分片 */
        public boolean isHlsTs()
        {
            return protocol.equals("http_hls") && format.equals("ts");
        }
    }

    /** getRoomPlayInfo 的结果 */
    public static final class PlayInfo
    {
        public final long roomId;
        public final int liveStatus;
        public final List<Stream> streams;

        PlayInfo(long roomId, int liveStatus, List<Stream> streams)
        {
            this.roomId = roomId;
            this.liveStatus = liveStatus;
            this.streams = Collections.unmodifiableList(streams);
        }

        public boolean isLive()
        {
            return liveStatus == STATUS_LIVE;
        }
    }

    /** 直播弹幕的连接信息 */
    public static final class DanmuInfo
    {
        public final String token;
        /** 弹幕服务器主机名（wss 端口是 443 的才收） */
        public final List<String> hosts;

        DanmuInfo(String token, List<String> hosts)
        {
            this.token = token;
            this.hosts = Collections.unmodifiableList(hosts);
        }
    }

    /** 直播接口的错误码 → 语言键 */
    static void checkCode(JsonObject root) throws BiliData.BiliException
    {
        int code = BiliData.getInt(root, "code", -1);
        if(code == 0)
            return;
        if(code == 60004 || code == 19002000 || code == 1)
            throw new BiliData.BiliException("cfm.tv.live.not_found", "code " + code);
        BiliData.checkCode(root);
    }

    /** room_init 的返回 */
    public static Room parseRoomInit(JsonObject root) throws BiliData.BiliException
    {
        checkCode(root);
        JsonObject data = BiliData.getObject(root, "data");
        if(data == null)
            throw new BiliData.BiliException("cfm.tv.live.not_found", "no data");
        long room = BiliData.getLong(data, "room_id", 0);
        if(room <= 0)
            throw new BiliData.BiliException("cfm.tv.live.not_found", "no room_id");
        if(BiliData.getBoolean(data, "encrypted") && !BiliData.getBoolean(data, "pwd_verified"))
            throw new BiliData.BiliException("cfm.tv.live.locked", "encrypted");
        if(BiliData.getBoolean(data, "is_locked"))
            throw new BiliData.BiliException("cfm.tv.live.locked", "locked");
        return new Room(room, BiliData.getInt(data, "live_status", STATUS_OFFLINE));
    }

    /** getRoomPlayInfo 的返回（没开播时没有 playurl_info，流列表是空的） */
    public static PlayInfo parsePlayInfo(JsonObject root) throws BiliData.BiliException
    {
        checkCode(root);
        JsonObject data = BiliData.getObject(root, "data");
        if(data == null)
            throw new BiliData.BiliException("cfm.tv.bili.error", "no data");
        long room = BiliData.getLong(data, "room_id", 0);
        int status = BiliData.getInt(data, "live_status", STATUS_OFFLINE);
        List<Stream> list = new ArrayList<>();
        JsonObject info = BiliData.getObject(data, "playurl_info");
        JsonObject playurl = info == null ? null : BiliData.getObject(info, "playurl");
        JsonArray streams = playurl == null ? null : BiliData.getArray(playurl, "stream");
        if(streams != null)
        {
            for(JsonElement se : streams)
            {
                if(!se.isJsonObject())
                    continue;
                String protocol = BiliData.getString(se.getAsJsonObject(), "protocol_name", "");
                JsonArray formats = BiliData.getArray(se.getAsJsonObject(), "format");
                if(formats == null)
                    continue;
                for(JsonElement fe : formats)
                {
                    if(!fe.isJsonObject())
                        continue;
                    String format = BiliData.getString(fe.getAsJsonObject(), "format_name", "");
                    JsonArray codecs = BiliData.getArray(fe.getAsJsonObject(), "codec");
                    if(codecs == null)
                        continue;
                    for(JsonElement ce : codecs)
                    {
                        if(!ce.isJsonObject())
                            continue;
                        Stream s = parseCodec(protocol, format, ce.getAsJsonObject());
                        if(s != null)
                            list.add(s);
                    }
                }
            }
        }
        return new PlayInfo(room, status, list);
    }

    @Nullable
    private static Stream parseCodec(String protocol, String format, JsonObject c)
    {
        String base = BiliData.getString(c, "base_url", "");
        JsonArray infos = BiliData.getArray(c, "url_info");
        if(base.isEmpty() || infos == null)
            return null;
        List<URI> urls = new ArrayList<>();
        for(JsonElement ie : infos)
        {
            if(!ie.isJsonObject())
                continue;
            String host = BiliData.getString(ie.getAsJsonObject(), "host", "");
            String extra = BiliData.getString(ie.getAsJsonObject(), "extra", "");
            if(host.isEmpty())
                continue;
            URI u = HostPolicy.upgrade(host + base + extra);
            if(u != null && !urls.contains(u))
                urls.add(u);
        }
        if(urls.isEmpty())
            return null;
        List<Integer> accept = new ArrayList<>();
        JsonArray aq = BiliData.getArray(c, "accept_qn");
        if(aq != null)
        {
            for(JsonElement e : aq)
            {
                try
                {
                    if(e.isJsonPrimitive())
                        accept.add(e.getAsInt());
                }
                catch(NumberFormatException ex)
                {
                    // 跳过
                }
            }
        }
        return new Stream(protocol, format, BiliData.getString(c, "codec_name", ""), BiliData.getInt(c, "current_qn", 0), accept, urls);
    }

    /**
     * 选一路：HTTP-FLV 优先（一条连接，最省事），其次 HLS 的 TS；同一种里 H.264 优先，其次 H.265。都没有返回 null。
     */
    @Nullable
    public static Stream select(List<Stream> streams)
    {
        Stream best = null;
        int bestRank = Integer.MAX_VALUE;
        for(Stream s : streams)
        {
            int rank = rank(s);
            if(rank < bestRank)
            {
                best = s;
                bestRank = rank;
            }
        }
        return best;
    }

    private static int rank(Stream s)
    {
        int container;
        if(s.isFlv())
            container = 0;
        else if(s.isHlsTs())
            container = 1;
        else
            return Integer.MAX_VALUE;
        int codec;
        if(s.codec.equals("avc"))
            codec = 0;
        else if(s.codec.equals("hevc"))
            codec = 2;
        else
            return Integer.MAX_VALUE;
        return codec + container;
    }

    /** getDanmuInfo 的返回：只收 wss 端口是 443 的主机（白名单里再核对主机名） */
    public static DanmuInfo parseDanmuInfo(JsonObject root) throws BiliData.BiliException
    {
        checkCode(root);
        JsonObject data = BiliData.getObject(root, "data");
        if(data == null)
            throw new BiliData.BiliException("cfm.tv.bili.error", "no data");
        String token = BiliData.getString(data, "token", "");
        if(token.isEmpty() || token.length() > 4096)
            throw new BiliData.BiliException("cfm.tv.bili.error", "no token");
        List<String> hosts = new ArrayList<>();
        JsonArray list = BiliData.getArray(data, "host_list");
        if(list != null)
        {
            for(JsonElement e : list)
            {
                if(!e.isJsonObject())
                    continue;
                String host = BiliData.getString(e.getAsJsonObject(), "host", "").toLowerCase(java.util.Locale.ROOT);
                if(BiliData.getInt(e.getAsJsonObject(), "wss_port", 0) == 443 && !host.isEmpty() && !hosts.contains(host))
                    hosts.add(host);
            }
        }
        return new DanmuInfo(token, hosts);
    }
}
