package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.UrlInput;

import com.mrcrayfish.furniture.web.Html;

import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

/**
 * 浏览器自己的页面（纯逻辑，文字从语言文件取）：开机主页、打不开时的错误页。错误页只说明原因，证书不对时不提供“继续访问”。
 */
public final class BuiltinPages
{
    /** 语言键 → 文字（参数按 %s 填） */
    public interface Tr extends BiFunction<String, Object[], String>
    {
        default String t(String key, Object... args)
        {
            return apply(key, args);
        }
    }

    public enum Error
    {
        NO_NETWORK, NAME_NOT_FOUND, DNS_FAILED, UNREACHABLE, REFUSED, TIMEOUT, RESET, CERTIFICATE, TLS, BAD_RESPONSE,
        TOO_LARGE, TOO_MANY_ELEMENTS, TOO_DEEP, MALFORMED, UNSUPPORTED_TYPE, TOO_MANY_REDIRECTS,
        UNSUPPORTED_SCHEME, BAD_URL, URL_TOO_LONG, RENDER_FAILED, OTHER
    }

    private BuiltinPages() {}

    public static byte[] home(Tr tr)
    {
        StringBuilder sb = new StringBuilder();
        head(sb, tr.t("cfm.browser.home.title"));
        sb.append("<div class=\"box\"><h1>").append(Html.escape(tr.t("cfm.browser.home.heading"))).append("</h1>\n");
        sb.append("<p>").append(Html.escape(tr.t("cfm.browser.home.intro"))).append("</p>\n<ul>\n");
        link(sb, "https://minebay.yr/", "MineBay", tr.t("cfm.browser.home.minebay"));
        link(sb, "https://test.yr/", "test.yr", tr.t("cfm.browser.home.test"));
        link(sb, "http://test.yr/", "test.yr（HTTP）", tr.t("cfm.browser.home.test_http"));
        sb.append("</ul>\n<p class=\"small\">").append(Html.escape(tr.t("cfm.browser.home.hint"))).append("</p></div>\n</body></html>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void link(StringBuilder sb, String url, String name, String desc)
    {
        sb.append("<li><a href=\"").append(Html.escape(url)).append("\">").append(Html.escape(name)).append("</a> <span class=\"small url-note\">")
                .append(Html.escape(url)).append("</span><br/>").append(Html.escape(desc)).append("</li>\n");
    }

    /**
     * @param url    要打开的网址（照原样显示）
     * @param detail 补充（证书问题、协议名、内部消息等），可以为 null
     */
    public static byte[] error(Tr tr, Error error, String url, String detail)
    {
        String key = "cfm.browser.error." + error.name().toLowerCase();
        StringBuilder sb = new StringBuilder();
        head(sb, tr.t("cfm.browser.error.title"));
        sb.append("<div class=\"box err\"><h1>").append(Html.escape(tr.t(key + ".heading"))).append("</h1>\n");
        if(url != null && !url.isEmpty())
            sb.append("<p class=\"url\">").append(Html.escape(UrlInput.display(url))).append("</p>\n");
        sb.append("<p>").append(Html.escape(tr.t(key + ".body", detail == null ? "" : detail))).append("</p>\n");
        if(detail != null && !detail.isEmpty() && error != Error.UNSUPPORTED_SCHEME)
            sb.append("<p class=\"small\">").append(Html.escape(tr.t("cfm.browser.error.detail", detail))).append("</p>\n");
        sb.append("<p class=\"small\">").append(Html.escape(tr.t("cfm.browser.error.kept"))).append("</p></div>\n</body></html>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 纯文字（text/plain）包成一页 */
    public static byte[] plainText(String title, String text)
    {
        StringBuilder sb = new StringBuilder();
        head(sb, title);
        sb.append("<pre style=\"white-space: pre-wrap; font-size: 13px;\">").append(Html.escape(text)).append("</pre></body></html>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 一张图（image/*）包成一页 */
    public static byte[] image(String url)
    {
        StringBuilder sb = new StringBuilder();
        head(sb, url);
        sb.append("<p style=\"text-align: center;\"><img src=\"").append(Html.escape(url)).append("\" alt=\"\"/></p></body></html>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void head(StringBuilder sb, String title)
    {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
                .append(Html.escape(title)).append("</title><style type=\"text/css\">\n")
                .append("body { background: #eef1f5; margin: 0; padding: 24px; }\n")
                .append(".box { background: #fff; border: 1px solid #ccd3dc; padding: 12px 20px; }\n")
                .append(".err { border-left: 6px solid #c0392b; }\n")
                .append("h1 { font-size: 22px; margin: 4px 0 10px 0; }\n")
                .append(".url { color: #555; font-size: 12px; }\n")
                .append(".small { color: #777; font-size: 12px; }\n")
                .append(".url-note { margin-left: 8px; }\n")
                .append("li { margin: 6px 0; }\n")
                .append("</style></head><body>\n");
    }
}
