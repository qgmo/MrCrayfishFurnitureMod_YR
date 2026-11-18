package com.mrcrayfish.furniture.client.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HLS 播放列表（m3u8）的解析（纯逻辑，悠然服务器魔改，服主 11-18 直播）。只认直播用到的那一小部分：
 * {@code #EXT-X-MEDIA-SEQUENCE}、{@code #EXT-X-TARGETDURATION}、{@code #EXTINF}、{@code #EXT-X-ENDLIST}；
 * 主列表（{@code #EXT-X-STREAM-INF}）取第一路。加密（{@code #EXT-X-KEY} 不是 NONE）与 fMP4（{@code #EXT-X-MAP}）不支持。
 * 分片地址相对列表地址解析。
 */
final class HlsPlaylist
{
    /** 一个列表最多收多少个分片 */
    static final int MAX_SEGMENTS = 1000;

    static final class Segment
    {
        final long seq;
        final URI uri;
        final long durationMs;

        Segment(long seq, URI uri, long durationMs)
        {
            this.seq = seq;
            this.uri = uri;
            this.durationMs = durationMs;
        }
    }

    final long targetDurationMs;
    final boolean ended;
    final List<Segment> segments;
    /** 主列表时：第一路的地址（否则 null） */
    final URI variant;

    private HlsPlaylist(long targetDurationMs, boolean ended, List<Segment> segments, URI variant)
    {
        this.targetDurationMs = targetDurationMs;
        this.ended = ended;
        this.segments = Collections.unmodifiableList(segments);
        this.variant = variant;
    }

    static HlsPlaylist parse(String text, URI base) throws IOException
    {
        String[] lines = text.split("\r?\n");
        if(lines.length == 0 || !lines[0].trim().startsWith("#EXTM3U"))
            throw new IOException("not an m3u8 playlist");
        long sequence = 0;
        long target = 2000;
        boolean ended = false;
        long pendingDuration = -1;
        boolean variantNext = false;
        List<Segment> segments = new ArrayList<>();
        for(int i = 1; i < lines.length; i++)
        {
            String line = lines[i].trim();
            if(line.isEmpty())
                continue;
            if(line.startsWith("#"))
            {
                if(line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))
                    sequence = parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()), 0);
                else if(line.startsWith("#EXT-X-TARGETDURATION:"))
                    target = Math.max(500, Math.min(60_000, parseLong(line.substring("#EXT-X-TARGETDURATION:".length()), 2) * 1000));
                else if(line.startsWith("#EXTINF:"))
                    pendingDuration = parseSeconds(line.substring("#EXTINF:".length()));
                else if(line.startsWith("#EXT-X-ENDLIST"))
                    ended = true;
                else if(line.startsWith("#EXT-X-STREAM-INF"))
                    variantNext = true;
                else if(line.startsWith("#EXT-X-MAP"))
                    throw new IOException("fMP4 HLS is not supported");
                else if(line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE"))
                    throw new IOException("encrypted HLS is not supported");
                continue;
            }
            URI uri;
            try
            {
                uri = base.resolve(line);
            }
            catch(IllegalArgumentException e)
            {
                throw new IOException("bad segment address");
            }
            if(variantNext)
                return new HlsPlaylist(target, false, Collections.emptyList(), uri);
            if(segments.size() >= MAX_SEGMENTS)
                throw new IOException("too many segments");
            segments.add(new Segment(sequence + segments.size(), uri, pendingDuration < 0 ? target : pendingDuration));
            pendingDuration = -1;
        }
        return new HlsPlaylist(target, ended, segments, null);
    }

    private static long parseLong(String s, long def)
    {
        try
        {
            return Long.parseLong(s.trim());
        }
        catch(NumberFormatException e)
        {
            return def;
        }
    }

    /** "3.989," → 3989 ms */
    private static long parseSeconds(String s)
    {
        int comma = s.indexOf(',');
        String num = comma >= 0 ? s.substring(0, comma) : s;
        try
        {
            double sec = Double.parseDouble(num.trim());
            return sec >= 0 && sec < 3600 ? Math.round(sec * 1000) : -1;
        }
        catch(NumberFormatException e)
        {
            return -1;
        }
    }
}
