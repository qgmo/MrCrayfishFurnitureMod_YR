package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.web.Html;

import java.util.List;

/**
 * MineBay 网站的页面（纯逻辑：给定商品清单写出 XHTML）。物品的图标、名字由浏览器按 {@code data-yr-item} /
 * {@code data-yr-item-name} 在玩家本地画、本地翻译（服务端不知道玩家的语言）。
 */
public final class MineBayPages
{
    public static final int PER_PAGE = 8;
    /** 一次最多买几份 */
    public static final int MAX_QUANTITY = 16;

    private MineBayPages() {}

    /** 一件商品 */
    public static final class Entry
    {
        public final int index;
        /** 物品写法：注册名，有子类型时加 {@code @数据值} */
        public final String item;
        public final int count;
        public final String currency;
        public final int price;

        public Entry(int index, String item, int count, String currency, int price)
        {
            this.index = index;
            this.item = item;
            this.count = count;
            this.currency = currency;
            this.price = price;
        }
    }

    public static String shop(List<Entry> entries, int page, String token, String message, boolean messageOk)
    {
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        StringBuilder sb = new StringBuilder();
        head(sb, "MineBay");
        sb.append("<div class=\"top\"><span class=\"logo\">Mine<b>Bay</b></span> <span class=\"slogan\">方块世界的网上商城</span></div>\n");
        if(message != null && !message.isEmpty())
        {
            sb.append("<p class=\"").append(messageOk ? "ok" : "bad").append("\">").append(Html.escape(message)).append("</p>\n");
        }
        sb.append("<p class=\"hint\">付款从你的背包里扣，买到的东西直接放进背包（背包满了会掉在脚边）。一次最多买 ").append(MAX_QUANTITY).append(" 份。</p>\n");
        sb.append("<table class=\"items\">\n");
        int from = p * PER_PAGE;
        for(int i = from; i < Math.min(entries.size(), from + PER_PAGE); i++)
        {
            Entry e = entries.get(i);
            sb.append("<tr><td class=\"icon\"><span class=\"item\" data-yr-item=\"").append(Html.escape(e.item)).append("\"></span></td>");
            sb.append("<td class=\"name\"><span data-yr-item-name=\"").append(Html.escape(e.item)).append("\">").append(Html.escape(e.item)).append("</span>");
            if(e.count > 1)
                sb.append(" ×").append(e.count);
            sb.append("</td>");
            sb.append("<td class=\"price\"><span class=\"coin\" data-yr-item=\"").append(Html.escape(e.currency)).append("\"></span> ×").append(e.price).append("</td>");
            sb.append("<td class=\"buy\"><form method=\"post\" action=\"/buy\"><input type=\"hidden\" name=\"i\" value=\"").append(e.index)
                    .append("\"/><input type=\"hidden\" name=\"p\" value=\"").append(p)
                    .append("\"/><input type=\"hidden\" name=\"t\" value=\"").append(Html.escape(token))
                    .append("\"/><input class=\"qty\" type=\"text\" name=\"n\" value=\"1\" maxlength=\"2\"/> <button type=\"submit\">购买</button></form></td></tr>\n");
        }
        sb.append("</table>\n<p class=\"pages\">");
        if(p > 0)
            sb.append("<a href=\"/?p=").append(p - 1).append("\">上一页</a> ");
        sb.append("第 ").append(p + 1).append(" / ").append(pages).append(" 页");
        if(p < pages - 1)
            sb.append(" <a href=\"/?p=").append(p + 1).append("\">下一页</a>");
        sb.append("</p>\n");
        sb.append("<p class=\"foot\">MineBay 原是 MrCrayfish 家具模组里电脑上的商店，现在搬到了悠然服务器内网。</p>\n");
        sb.append("</body></html>\n");
        return sb.toString();
    }

    static void head(StringBuilder sb, String title)
    {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\"><head><title>").append(Html.escape(title)).append("</title>\n");
        sb.append("<style type=\"text/css\">\n");
        sb.append("body { margin: 0; background: #f4f4f4; color: #222; font-size: 14px; }\n");
        sb.append(".top { background: #e53238; color: #fff; padding: 10px 14px; }\n");
        sb.append(".logo { font-size: 26px; font-weight: bold; } .logo b { color: #f5af02; }\n");
        sb.append(".slogan { font-size: 13px; margin-left: 8px; }\n");
        sb.append(".hint { color: #666; font-size: 12px; margin: 8px 14px; }\n");
        sb.append(".ok { background: #dff0d8; color: #2b542c; margin: 8px 14px; padding: 6px 10px; }\n");
        sb.append(".bad { background: #f2dede; color: #a94442; margin: 8px 14px; padding: 6px 10px; }\n");
        sb.append("table.items { margin: 0 14px; border-collapse: collapse; width: 572px; background: #fff; }\n");
        sb.append("table.items td { border-bottom: 1px solid #ddd; padding: 4px 6px; vertical-align: middle; }\n");
        sb.append("td.icon { width: 36px; } td.price { width: 80px; } td.buy { width: 110px; text-align: right; }\n");
        sb.append(".item { display: inline-block; width: 32px; height: 32px; }\n");
        sb.append(".coin { display: inline-block; width: 16px; height: 16px; vertical-align: middle; }\n");
        sb.append("form { margin: 0; }\n");
        sb.append("input.qty { width: 24px; text-align: right; }\n");
        sb.append("button { padding: 2px 10px; background: #3665f3; color: #fff; border: 1px solid #2a50c8; }\n");
        sb.append(".pages { margin: 10px 14px; } .foot { color: #999; font-size: 11px; margin: 14px; }\n");
        sb.append("</style></head><body>\n");
    }
}
