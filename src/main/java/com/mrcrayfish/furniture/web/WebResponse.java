package com.mrcrayfish.furniture.web;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 网站的回应 */
public final class WebResponse
{
    public static final String XHTML = "application/xhtml+xml; charset=utf-8";

    public final int status;
    public final String reason;
    public final String contentType;
    public final byte[] body;
    /** 额外的首部（Content-Type、Content-Length、Connection、Date、Server 由服务器写） */
    public final Map<String, String> headers;

    public WebResponse(int status, String reason, String contentType, byte[] body, Map<String, String> headers)
    {
        this.status = status;
        this.reason = reason;
        this.contentType = contentType;
        this.body = body;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /** 200 一页 XHTML */
    public static WebResponse xhtml(String page)
    {
        return new WebResponse(200, "OK", XHTML, page.getBytes(StandardCharsets.UTF_8), Collections.<String, String>emptyMap());
    }

    /** 一页 XHTML，带指定的状态码（例如表单不合格时 422 回原页面并说明原因） */
    public static WebResponse xhtml(int status, String reason, String page)
    {
        return new WebResponse(status, reason, XHTML, page.getBytes(StandardCharsets.UTF_8), Collections.<String, String>emptyMap());
    }

    /** 303：提交表单后跳到结果页（浏览器改用 GET 打开 location） */
    public static WebResponse seeOther(String location)
    {
        return new WebResponse(303, "See Other", XHTML, errorPage(303, "See Other").getBytes(StandardCharsets.UTF_8),
                Collections.singletonMap("Location", location));
    }

    public static WebResponse error(int status, String reason)
    {
        return new WebResponse(status, reason, XHTML, errorPage(status, reason).getBytes(StandardCharsets.UTF_8), Collections.<String, String>emptyMap());
    }

    static String errorPage(int status, String reason)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" + status + " " + Html.escape(reason)
                + "</title></head><body><h1>" + status + " " + Html.escape(reason) + "</h1></body></html>\n";
    }
}
