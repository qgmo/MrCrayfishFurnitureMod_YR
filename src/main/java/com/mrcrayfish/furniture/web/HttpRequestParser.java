package com.mrcrayfish.furniture.web;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 解析 HTTP/1.x 请求（纯逻辑）：请求行、首部（最多 {@value #MAX_HEADER} 字节）、按 Content-Length 的正文
 * （最多 {@value #MAX_BODY} 字节）。不收分块上传（回 501）；HTTP/1.1 必须带 Host（RFC 9112 第 3.2 节）。
 */
public final class HttpRequestParser
{
    public static final int MAX_HEADER = 8 * 1024;
    public static final int MAX_BODY = 16 * 1024;

    private HttpRequestParser() {}

    /** 解析结果：还不完整、出错（回这个状态码）、或者完整 */
    public static final class Result
    {
        public final int errorStatus;
        public final String errorReason;
        public final String method;
        public final String target;
        public final Map<String, String> headers;
        public final byte[] body;

        Result(int errorStatus, String errorReason, String method, String target, Map<String, String> headers, byte[] body)
        {
            this.errorStatus = errorStatus;
            this.errorReason = errorReason;
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }

        static Result error(int status, String reason)
        {
            return new Result(status, reason, null, null, null, null);
        }

        public boolean isError()
        {
            return errorStatus != 0;
        }
    }

    /** 还不完整时返回 null */
    public static Result tryParse(byte[] b)
    {
        int end = indexOfHeaderEnd(b);
        if(end < 0)
            return b.length > MAX_HEADER ? Result.error(431, "Request Header Fields Too Large") : null;
        if(end > MAX_HEADER)
            return Result.error(431, "Request Header Fields Too Large");
        String head = new String(b, 0, end, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n", -1);
        String[] parts = lines[0].split(" ", -1);
        if(parts.length != 3 || !parts[2].matches("HTTP/1\\.[01]") || parts[1].isEmpty() || !parts[0].matches("[A-Z]{1,16}"))
            return Result.error(400, "Bad Request");
        String target = parts[1];
        if(!target.startsWith("/") || target.length() > 4096)
            return Result.error(400, "Bad Request");
        for(int i = 0; i < target.length(); i++)
        {
            char c = target.charAt(i);
            if(c <= 0x20 || c >= 0x7F)
                return Result.error(400, "Bad Request");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for(int i = 1; i < lines.length; i++)
        {
            int colon = lines[i].indexOf(':');
            if(colon <= 0)
                return Result.error(400, "Bad Request");
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+"))
                return Result.error(400, "Bad Request");
            String value = lines[i].substring(colon + 1).trim();
            headers.merge(name, value, (a, v) -> a + ", " + v);
        }
        if(parts[2].equals("HTTP/1.1") && !headers.containsKey("host"))
            return Result.error(400, "Bad Request");
        if(headers.containsKey("transfer-encoding"))
            return Result.error(501, "Not Implemented");
        int length = 0;
        String cl = headers.get("content-length");
        if(cl != null)
        {
            if(!cl.matches("\\d{1,9}"))
                return Result.error(400, "Bad Request");
            length = Integer.parseInt(cl);
            if(length > MAX_BODY)
                return Result.error(413, "Content Too Large");
        }
        int start = end + 4;
        if(b.length - start < length)
            return null;
        byte[] body = new byte[length];
        System.arraycopy(b, start, body, 0, length);
        return new Result(0, null, parts[0], target, headers, body);
    }

    static int indexOfHeaderEnd(byte[] b)
    {
        for(int i = 0; i + 3 < b.length; i++)
        {
            if(b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n')
                return i;
        }
        return -1;
    }
}
