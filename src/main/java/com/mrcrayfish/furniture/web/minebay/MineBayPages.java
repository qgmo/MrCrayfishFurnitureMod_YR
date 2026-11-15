package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.computer.FormEncoding;
import com.mrcrayfish.furniture.web.Html;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MineBay 网站的页面（纯逻辑：给定数据写出 XHTML）。浏览器没有 JavaScript、只认 CSS 2.1（加一点 CSS3），
 * 所以布局用表格与 inline-block，交互全用链接与表单。页面上限 64 KB、2000 个元素、64 层（{@code PageLimits}）。
 * <p>
 * 物品的图标、名字由浏览器按 {@code data-yr-item} / {@code data-yr-item-name} 在玩家本地画、本地翻译；
 * 附魔、药水等说明用 {@code data-yr-lang} 让浏览器按玩家的语言翻译（服务端不知道玩家的语言）。
 */
public final class MineBayPages
{
    /** 订单页每页几单 */
    public static final int ORDERS_PER_PAGE = 5;
    /** 订单列表里每单最多列几种商品（多的点进订单详情看） */
    public static final int ORDER_PREVIEW_ITEMS = 6;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);

    private MineBayPages() {}

    /** 每件商品现在还能卖几份（-1 不限）、多久补货 */
    public interface StockView
    {
        long remaining(Listing l);

        String refill(Listing l);
    }

    /** 不看库存（都不限） */
    public static final StockView NO_STOCK = new StockView()
    {
        @Override
        public long remaining(Listing l)
        {
            return -1;
        }

        @Override
        public String refill(Listing l)
        {
            return "";
        }
    };

    /** 看页面的人（MineBay 登录着的角色；谁坐在电脑前不管） */
    public static final class Viewer
    {
        /** 登录的玩家名；没登录为 null */
        @Nullable
        public final String player;
        public final int cartCount;
        /** 表单令牌 */
        public final String token;
        public final StockView stock;
        /** 服务器开着 id.yr（能登录） */
        public final boolean loginAvailable;

        public Viewer(@Nullable String player, int cartCount, String token)
        {
            this(player, cartCount, token, NO_STOCK, true);
        }

        public Viewer(@Nullable String player, int cartCount, String token, StockView stock, boolean loginAvailable)
        {
            this.player = player;
            this.cartCount = cartCount;
            this.token = token;
            this.stock = stock;
            this.loginAvailable = loginAvailable;
        }

        public static Viewer anonymous(String token)
        {
            return new Viewer(null, 0, token, NO_STOCK, true);
        }
    }

    /** 页面顶上的提示 */
    public static final class Notice
    {
        public final boolean ok;
        public final String text;

        public Notice(boolean ok, String text)
        {
            this.ok = ok;
            this.text = text;
        }

        public static Notice ok(String text)
        {
            return new Notice(true, text);
        }

        public static Notice bad(String text)
        {
            return new Notice(false, text);
        }
    }

    // ---------------------------------------------------------------- 列表

    /**
     * 商品列表（首页、分类、搜索结果）。
     * @param all   全部在卖的商品（算件数用）
     * @param shown 筛选、排序后的商品
     * @param page  页码（从 1 起；超出时停在最后一页）
     */
    public static String list(Viewer v, List<Listing> all, List<Listing> shown, @Nullable String category, String query, ShopParams.Sort sort, int page, @Nullable Notice notice)
    {
        int pages = Math.max(1, (shown.size() + ShopParams.PER_PAGE - 1) / ShopParams.PER_PAGE);
        int p = Math.max(1, Math.min(page, pages));
        Categories.Category cat = Categories.get(category);
        String title = !query.isEmpty() ? "搜索“" + query + "”" : cat != null ? cat.name : null;
        StringBuilder sb = new StringBuilder(16 * 1024);
        begin(sb, title == null ? "MineBay - 方块世界的网上商城" : title + " - MineBay", v, query, !query.isEmpty() ? null : category == null ? "" : category);
        notice(sb, notice);
        boolean home = category == null && query.isEmpty();
        if(!home)
            sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a>");
        if(cat != null)
            sb.append(" &#8250; ").append(query.isEmpty() ? "<b>" + Html.escape(cat.name) + "</b>" : "<a href=\"" + attr(listUrl(cat.id, "", ShopParams.Sort.DEFAULT, 1)) + "\">" + Html.escape(cat.name) + "</a>");
        if(!query.isEmpty())
            sb.append(" &#8250; <b>搜索“").append(Html.escape(query)).append("”</b>");
        if(!home)
            sb.append("</div>\n");
        if(home && p == 1)
        {
            sb.append("<div class=\"banner\"><div class=\"bt\">欢迎来到 MineBay！</div>");
            sb.append("<table class=\"perks\"><tr><td><b>pay.yr 付款</b><br/>绿宝石、悠然元都收</td><td><b>购物车随存档</b><br/>换电脑也还在</td><td><b>付款即发货</b><br/>直接进背包</td></tr></table></div>\n");
        }
        if(!query.isEmpty() && !shown.isEmpty() && category == null)
        {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for(Listing l : shown)
                counts.merge(l.category, 1, Integer::sum);
            if(counts.size() > 1)
            {
                sb.append("<div class=\"facets\">按分类看：");
                for(Categories.Category c : Categories.all())
                {
                    Integer n = counts.get(c.id);
                    if(n != null)
                        sb.append("<a href=\"").append(attr(listUrl(c.id, query, sort, 1))).append("\">").append(Html.escape(c.name)).append("</a> <span class=\"dim\">(").append(n).append(")</span> ");
                }
                sb.append("</div>\n");
            }
        }
        sb.append("<table class=\"bar\"><tr><td>共 <b>").append(shown.size()).append("</b> 件商品");
        if(!query.isEmpty() && shown.size() < all.size())
            sb.append(" <span class=\"dim\">（全站 ").append(all.size()).append(" 件）</span>");
        sb.append("</td><td class=\"r\">排序：");
        boolean first = true;
        for(ShopParams.Sort s : ShopParams.Sort.values())
        {
            if(!first)
                sb.append(" <span class=\"sep\">|</span> ");
            first = false;
            if(s == sort)
                sb.append("<b>").append(s.label).append("</b>");
            else
                sb.append("<a href=\"").append(attr(listUrl(category, query, s, 1))).append("\">").append(s.label).append("</a>");
        }
        sb.append("</td></tr></table>\n");
        if(shown.isEmpty())
        {
            sb.append("<div class=\"empty\">");
            if(!query.isEmpty())
                sb.append("没有找到“<b>").append(Html.escape(query)).append("</b>”。<br/>可以搜中文名、英文名或物品 ID。");
            else
                sb.append("这个分类里暂时没有商品。");
            sb.append("<br/><a href=\"/\">看看全部商品</a></div>\n");
        }
        else
        {
            sb.append("<table class=\"grid\">\n");
            int from = (p - 1) * ShopParams.PER_PAGE;
            int to = Math.min(shown.size(), from + ShopParams.PER_PAGE);
            for(int i = from; i < to; i += 4)
            {
                sb.append("<tr>");
                for(int k = i; k < i + 4; k++)
                {
                    if(k < to)
                        card(sb, v, shown.get(k));
                    else
                        sb.append("<td class=\"cell\"></td>");
                }
                sb.append("</tr>\n");
            }
            sb.append("</table>\n");
            pager(sb, p, pages, n -> listUrl(category, query, sort, n));
        }
        end(sb);
        return sb.toString();
    }

    private static void card(StringBuilder sb, Viewer v, Listing l)
    {
        String url = itemUrl(l.id);
        sb.append("<td class=\"cell\"><div class=\"card\">");
        sb.append("<div class=\"pic\"><a class=\"i48\" href=\"").append(attr(url)).append("\" data-yr-item=\"").append(attr(l.icon)).append("\"></a></div>");
        sb.append("<div class=\"nm\">");
        nameLink(sb, l.name, l.sortName, l.icon, url);
        if(l.count > 1)
            sb.append(" <span class=\"cnt\">&#215;").append(l.count).append("</span>");
        sb.append("</div><div class=\"pr\">");
        price(sb, l.currency, l.price, false);
        long left = v.stock.remaining(l);
        if(left > 0)
            sb.append(" <span class=\"stock\">剩 ").append(left).append(" 份</span>");
        sb.append("</div>");
        if(left == 0)
            sb.append("<div><span class=\"btn off\">已卖完</span></div>");
        else
            sb.append("<form method=\"post\" action=\"/cart/add\"><input type=\"hidden\" name=\"id\" value=\"").append(attr(l.id))
                    .append("\"/><input type=\"hidden\" name=\"t\" value=\"").append(attr(v.token)).append("\"/><button type=\"submit\" class=\"btn add\">加入购物车</button></form>");
        sb.append("</div></td>");
    }

    // ---------------------------------------------------------------- 详情

    /** 商品详情。{@code typedQuantity} 是上次提交没成功时玩家填的数量（原样填回去）。 */
    public static String item(Viewer v, Listing l, int inCart, @Nullable Notice notice, @Nullable String typedQuantity)
    {
        Categories.Category cat = Categories.get(l.category);
        StringBuilder sb = new StringBuilder(8 * 1024);
        begin(sb, l.sortName + " - MineBay", v, "", l.category);
        notice(sb, notice);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a>");
        if(cat != null)
            sb.append(" &#8250; <a href=\"").append(attr(listUrl(cat.id, "", ShopParams.Sort.DEFAULT, 1))).append("\">").append(Html.escape(cat.name)).append("</a>");
        sb.append(" &#8250; <b>");
        name(sb, l.name, l.sortName, l.icon);
        sb.append("</b></div>\n");
        sb.append("<table class=\"detail\"><tr><td class=\"big\"><div class=\"bigbox\"><span class=\"i96\" data-yr-item=\"").append(attr(l.icon)).append("\"></span></div>");
        sb.append("<div class=\"dim small\">鼠标指着图标看说明</div></td>\n<td class=\"info\"><h1>");
        name(sb, l.name, l.sortName, l.icon);
        sb.append("</h1>\n<table class=\"facts\">");
        sb.append("<tr><th>价格</th><td><span class=\"bigprice\">");
        price(sb, l.currency, l.price, true);
        sb.append("</span> <span class=\"dim\">/ 份</span></td></tr>\n");
        sb.append("<tr><th>每份</th><td>").append(l.count).append(" 个</td></tr>\n");
        if(cat != null)
            sb.append("<tr><th>分类</th><td><a href=\"").append(attr(listUrl(cat.id, "", ShopParams.Sort.DEFAULT, 1))).append("\">").append(Html.escape(cat.name)).append("</a></td></tr>\n");
        long left = v.stock.remaining(l);
        if(l.stock != null)
        {
            String wait = v.stock.refill(l);
            sb.append("<tr><th>库存</th><td>").append(left == 0 ? "<span class=\"bad\">已卖完</span>" : "剩 <b>" + left + "</b> 份").append(" <span class=\"dim\">（")
                    .append(Html.escape(Stock.describe(l.stock))).append(left == 0 && !wait.isEmpty() ? "；" + Html.escape(wait) : "").append("）</span></td></tr>\n");
        }
        sb.append("<tr><th>发货</th><td>付款后放进背包</td></tr>\n</table>\n");
        int max = Cart.limit(l.maxPerOrder);
        sb.append("<form method=\"post\" action=\"/cart/add\" class=\"buybox\"><input type=\"hidden\" name=\"id\" value=\"").append(attr(l.id))
                .append("\"/><input type=\"hidden\" name=\"t\" value=\"").append(attr(v.token)).append("\"/>");
        sb.append("<div class=\"qtyline\">数量：<input type=\"text\" class=\"qty\" name=\"n\" maxlength=\"2\" value=\"").append(attr(typedQuantity == null ? "1" : clip(typedQuantity, 8)))
                .append("\"/> 份 <span class=\"dim\">（1–").append(max).append("）</span></div>");
        if(left == 0)
            sb.append("<div><span class=\"btn off big\">已卖完</span></div>");
        else
        {
            sb.append("<div><button type=\"submit\" name=\"go\" value=\"cart\" class=\"btn add big\">加入购物车</button> &#160;");
            sb.append("<button type=\"submit\" name=\"go\" value=\"buy\" class=\"btn buy big\">立即购买</button></div>");
        }
        if(inCart > 0)
            sb.append("<div class=\"incart\">购物车里已有 ").append(inCart).append(" 份，<a href=\"/cart\">去看看</a></div>");
        sb.append("</form>\n</td></tr></table>\n");
        sb.append("<div class=\"box\"><div class=\"boxh\">商品说明</div><div class=\"boxb\">");
        if(l.description.isEmpty())
            sb.append("<p class=\"dim\">卖家没有写说明。</p>");
        else
        {
            sb.append("<ul class=\"desc\">");
            for(List<Listing.Part> line : l.description)
            {
                sb.append("<li>");
                for(Listing.Part part : line)
                {
                    if(part.langKey != null)
                        sb.append("<span data-yr-lang=\"").append(attr(part.langKey)).append("\">").append(Html.escape(part.text)).append("</span>");
                    else
                        sb.append(Html.escape(part.text));
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("<p class=\"dim small\">商品编号：").append(Html.escape(l.id)).append("</p></div></div>\n");
        end(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 购物车

    /** @param typed 上次提交没成功时玩家在某一行填的数量（商品编号 → 原样的文字），原样填回去 */
    public static String cart(Viewer v, Cart cart, Function<String, Listing> lookup, Checkout.Plan plan, @Nullable Notice notice, Map<String, String> typed)
    {
        StringBuilder sb = new StringBuilder(16 * 1024);
        begin(sb, "购物车 - MineBay", v, "", null);
        notice(sb, notice);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <b>购物车</b></div>\n<h2>我的购物车</h2>\n");
        if(v.player == null)
        {
            loginBox(sb, v, "/cart");
            end(sb);
            return sb.toString();
        }
        if(cart.isEmpty())
        {
            sb.append("<div class=\"empty\">购物车是空的。<br/><a href=\"/\">去逛逛</a></div>\n");
            end(sb);
            return sb.toString();
        }
        sb.append("<table class=\"lines\"><tr class=\"h\"><th colspan=\"2\">商品</th><th>单价</th><th class=\"q\">数量</th><th class=\"r\">小计</th></tr>\n");
        for(Cart.Line line : cart.lines())
        {
            Listing l = lookup.apply(line.id);
            Checkout.Unavailable why = plan.unavailable.get(line.id);
            sb.append("<tr").append(why != null ? " class=\"off\"" : "").append(">");
            if(l == null)
            {
                sb.append("<td class=\"ic\"><span class=\"i32 gone\"></span></td><td class=\"nm\"><span class=\"dim\">").append(Html.escape(line.id)).append("</span>");
                sb.append("<div class=\"warn\">已下架，不能结算（删除后就不显示了）</div></td><td>—</td>");
            }
            else
            {
                String url = itemUrl(l.id);
                sb.append("<td class=\"ic\"><a class=\"i32\" href=\"").append(attr(url)).append("\" data-yr-item=\"").append(attr(l.icon)).append("\"></a></td><td class=\"nm\">");
                nameLink(sb, l.name, l.sortName, l.icon, url);
                if(l.count > 1)
                    sb.append(" <span class=\"cnt\">&#215;").append(l.count).append("</span>");
                if(why == Checkout.Unavailable.GONE)
                    sb.append("<div class=\"warn\">已下架，不能结算</div>");
                else if(why == Checkout.Unavailable.LIMIT)
                    sb.append("<div class=\"warn\">这件一单最多买 ").append(Cart.limit(l.maxPerOrder)).append(" 份，请改少一点</div>");
                else if(why == Checkout.Unavailable.STOCK)
                {
                    long left = v.stock.remaining(l);
                    sb.append("<div class=\"warn\">").append(left <= 0 ? "已卖完" : "库存只剩 " + left + " 份").append("</div>");
                }
                else if(l.stock != null)
                    sb.append("<div class=\"stock\">剩 ").append(v.stock.remaining(l)).append(" 份</div>");
                sb.append("</td><td class=\"up\">");
                price(sb, l.currency, l.price, false);
                sb.append("</td>");
            }
            String shown = typed.containsKey(line.id) ? clip(typed.get(line.id), 8) : Integer.toString(line.quantity());
            sb.append("<td class=\"q\"><form method=\"post\" action=\"/cart/update\"><input type=\"hidden\" name=\"id\" value=\"").append(attr(line.id))
                    .append("\"/><input type=\"hidden\" name=\"t\" value=\"").append(attr(v.token)).append("\"/>");
            if(l != null)
                sb.append("<input type=\"text\" class=\"qty\" name=\"n\" maxlength=\"2\" value=\"").append(attr(shown)).append("\"/> <button type=\"submit\" name=\"act\" value=\"set\" class=\"btn small\">更新</button> ");
            sb.append("<button type=\"submit\" name=\"act\" value=\"remove\" class=\"btn small del\">删除</button></form></td><td class=\"r\">");
            if(l != null && why == null)
                price(sb, l.currency, l.price * line.quantity(), true);
            else
                sb.append("—");
            sb.append("</td></tr>\n");
        }
        sb.append("</table>\n");
        sb.append("<table class=\"sum\"><tr><td class=\"note\">最多 ").append(Cart.MAX_LINES).append(" 种，每种 ").append(Cart.MAX_QUANTITY)
                .append(" 份。</td><td class=\"tot\">");
        if(plan.isEmpty())
            sb.append("<div class=\"totl\">没有能结算的商品</div>");
        else
        {
            sb.append("<div class=\"totl\">合计（").append(plan.items.size()).append(" 种）</div>");
            totals(sb, plan.totals);
            sb.append("<div class=\"go\"><a class=\"btn buy big\" href=\"/checkout\">去结算 &#187;</a></div>");
        }
        sb.append("<div class=\"go small\"><a href=\"/\">&#171; 继续购物</a></div></td></tr></table>\n");
        end(sb);
        return sb.toString();
    }

    /** 删除前确认（玩家放进购物车的东西不经确认不丢） */
    public static String removeConfirm(Viewer v, String id, @Nullable Listing l, int quantity)
    {
        StringBuilder sb = new StringBuilder(8 * 1024);
        begin(sb, "删除商品 - MineBay", v, "", null);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <a href=\"/cart\">购物车</a> &#8250; <b>删除商品</b></div>\n");
        sb.append("<div class=\"confirm\"><table class=\"oi\"><tr><td class=\"ic\">");
        if(l != null)
            sb.append("<span class=\"i32\" data-yr-item=\"").append(attr(l.icon)).append("\"></span>");
        sb.append("</td><td>确定要把 <b>");
        if(l != null)
            name(sb, l.name, l.sortName, l.icon);
        else
            sb.append(Html.escape(id));
        sb.append("</b>（").append(quantity).append(" 份）从购物车里删除吗？</td></tr></table>");
        sb.append("<form method=\"post\" action=\"/cart/update\"><input type=\"hidden\" name=\"id\" value=\"").append(attr(id))
                .append("\"/><input type=\"hidden\" name=\"t\" value=\"").append(attr(v.token)).append("\"/>");
        sb.append("<div class=\"go\"><button type=\"submit\" name=\"act\" value=\"remove_yes\" class=\"btn del\">删除</button> &#160; <a class=\"btn\" href=\"/cart\">不删，回购物车</a></div></form></div>\n");
        end(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 结算

    /**
     * 结算确认。{@code nonce}（确认码）为 null 时不给“提交订单”；{@code locationNonce} 是给定位模块的一次性随机数（浏览器提交时换成签名的位置证明）。
     * @param buyId 立即购买时的商品编号；结算购物车时为 null
     */
    public static String checkout(Viewer v, Checkout.Plan plan, Function<String, Listing> lookup, @Nullable String nonce, @Nullable String locationNonce,
                                  @Nullable String buyId, @Nullable Notice notice)
    {
        String back = buyId == null ? "/cart" : itemUrl(buyId);
        StringBuilder sb = new StringBuilder(16 * 1024);
        begin(sb, "确认订单 - MineBay", v, "", null);
        sb.append("<table class=\"steps\"><tr><td class=\"done\">1. ").append(buyId == null ? "购物车" : "选好商品").append("</td><td class=\"on\">2. 确认订单</td><td>3. 付款</td><td>4. 完成</td></tr></table>\n");
        notice(sb, notice);
        sb.append("<h2>请核对订单</h2>\n");
        sb.append("<table class=\"lines\"><tr class=\"h\"><th colspan=\"2\">商品</th><th>单价</th><th class=\"q\">数量</th><th class=\"r\">小计</th></tr>\n");
        for(Checkout.Item i : plan.items)
        {
            Listing l = lookup.apply(i.offer.id());
            sb.append("<tr><td class=\"ic\"><span class=\"i32\" data-yr-item=\"").append(attr(l == null ? "" : l.icon)).append("\"></span></td><td class=\"nm\">");
            if(l != null)
            {
                name(sb, l.name, l.sortName, l.icon);
                if(l.count > 1)
                    sb.append(" <span class=\"cnt\">&#215;").append(l.count).append("</span>");
                if(l.stock != null)
                    sb.append("<div class=\"stock\">剩 ").append(v.stock.remaining(l)).append(" 份</div>");
            }
            sb.append("</td><td class=\"up\">");
            price(sb, i.offer.currency(), i.offer.unitPrice(), false);
            sb.append("</td><td class=\"q\">").append(i.quantity).append(" 份</td><td class=\"r\">");
            price(sb, i.offer.currency(), i.cost, true);
            sb.append("</td></tr>\n");
        }
        sb.append("</table>\n");
        if(!plan.unavailable.isEmpty())
            sb.append("<p class=\"warn\">另有 ").append(plan.unavailable.size()).append(" 种买不了（下架、超过上限或库存不够），留在购物车里。</p>\n");
        sb.append("<div class=\"box paybox\"><div class=\"boxh\">合计</div><div class=\"boxb\">");
        totals(sb, plan.totals);
        sb.append("<div class=\"dim small\">在 pay.yr 付款，绿宝石、悠然元都能付（1 绿宝石 = 2 元）。</div></div></div>\n");
        if(nonce != null && locationNonce != null && v.player != null)
        {
            sb.append("<form method=\"post\" action=\"/checkout\" class=\"confirm\"><input type=\"hidden\" name=\"k\" value=\"").append(attr(nonce))
                    .append("\"/><input type=\"hidden\" name=\"t\" value=\"").append(attr(v.token)).append("\"/>");
            sb.append("<input type=\"x-yr-location\" name=\"loc\" value=\"").append(attr(locationNonce)).append("\"/>");
            sb.append("<div>收货人：<b>").append(Html.escape(v.player)).append("</b>（要在线、在这台电脑 8 格内）</div>");
            sb.append("<div class=\"go\"><button type=\"submit\" class=\"btn buy big\">提交订单，去付款</button> &#160; <a href=\"").append(attr(back)).append("\">返回修改</a></div></form>\n");
        }
        else
            sb.append("<p><a href=\"").append(attr(back)).append("\">&#171; 返回").append(buyId == null ? "购物车" : "商品").append("</a></p>\n");
        end(sb);
        return sb.toString();
    }

    /** 要登录才能看的页面（购物车、结算、订单） */
    public static String loginNeeded(Viewer v, String next)
    {
        StringBuilder sb = new StringBuilder(4 * 1024);
        begin(sb, "请登录 - MineBay", v, "", null);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <b>登录</b></div>\n");
        loginBox(sb, v, next);
        end(sb);
        return sb.toString();
    }

    private static void loginBox(StringBuilder sb, Viewer v, String next)
    {
        sb.append("<div class=\"empty\">");
        if(!v.loginAvailable)
            sb.append("服务器没有开游戏角色登录（id.yr），暂时不能购物。");
        else
            sb.append("登录后才能用购物车、下单。<form method=\"get\" action=\"/login\"><input type=\"hidden\" name=\"next\" value=\"").append(attr(next))
                    .append("\"/><button type=\"submit\" class=\"btn buy big\">用游戏角色登录</button></form>");
        sb.append("<br/><a href=\"/\">继续逛逛</a></div>\n");
    }

    /** 从 pay.yr 回来但还没付款 */
    public static String unpaid(Viewer v, String checkoutUrl)
    {
        StringBuilder sb = new StringBuilder(4 * 1024);
        begin(sb, "还没付款 - MineBay", v, "", null);
        sb.append("<table class=\"steps\"><tr><td class=\"done\">1. 选好商品</td><td class=\"done\">2. 确认订单</td><td class=\"on\">3. 付款</td><td>4. 完成</td></tr></table>\n");
        sb.append("<div class=\"empty\"><b>还没付款</b><br/><a class=\"btn buy big\" href=\"").append(attr(checkoutUrl)).append("\">去 pay.yr 付款</a> &#160; <a href=\"/cart\">回购物车</a></div>\n");
        end(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 订单

    /** @param highlight 刚下的那一单（标出来）；没有为 -1 */
    public static String orders(Viewer v, List<Orders.Order> orders, int page, @Nullable Notice notice, long highlight)
    {
        int pages = Math.max(1, (orders.size() + ORDERS_PER_PAGE - 1) / ORDERS_PER_PAGE);
        int p = Math.max(1, Math.min(page, pages));
        StringBuilder sb = new StringBuilder(16 * 1024);
        begin(sb, "我的订单 - MineBay", v, "", null);
        if(highlight >= 0 && notice != null && notice.ok)
            sb.append("<table class=\"steps\"><tr><td class=\"done\">1. 选好商品</td><td class=\"done\">2. 确认订单</td><td class=\"done\">3. 付款</td><td class=\"on\">4. 完成</td></tr></table>\n");
        notice(sb, notice);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <b>我的订单</b></div>\n<h2>我的订单</h2>\n");
        if(v.player == null)
        {
            loginBox(sb, v, "/orders");
            end(sb);
            return sb.toString();
        }
        if(orders.isEmpty())
        {
            sb.append("<div class=\"empty\">还没有订单。<br/><a href=\"/\">去逛逛</a></div>\n");
            end(sb);
            return sb.toString();
        }
        sb.append("<p class=\"dim small\">共 ").append(orders.size()).append(" 单（保留最近 ").append(Orders.MAX_ORDERS).append(" 单，北京时间）。</p>\n");
        int from = (p - 1) * ORDERS_PER_PAGE;
        for(int i = from; i < Math.min(orders.size(), from + ORDERS_PER_PAGE); i++)
            order(sb, orders.get(i), ORDER_PREVIEW_ITEMS, orders.get(i).number == highlight);
        pager(sb, p, pages, n -> "/orders?p=" + n);
        end(sb);
        return sb.toString();
    }

    /** 一张订单的全部商品 */
    public static String orderDetail(Viewer v, Orders.Order o)
    {
        StringBuilder sb = new StringBuilder(16 * 1024);
        begin(sb, "订单 " + number(o.number) + " - MineBay", v, "", null);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <a href=\"/orders\">我的订单</a> &#8250; <b>").append(number(o.number)).append("</b></div>\n");
        order(sb, o, Cart.MAX_LINES, false);
        sb.append("<p><a href=\"/orders\">&#171; 返回我的订单</a></p>\n");
        end(sb);
        return sb.toString();
    }

    static String number(long n)
    {
        return String.format("MB%08d", n);
    }

    private static void order(StringBuilder sb, Orders.Order o, int maxItems, boolean highlight)
    {
        sb.append("<div class=\"order").append(highlight ? " new" : "").append("\"><table class=\"oh\"><tr><td>订单号 <b>").append(number(o.number)).append("</b></td><td>")
                .append(TIME.format(Instant.ofEpochMilli(o.time))).append("</td><td class=\"r\">花费 ");
        boolean first = true;
        for(Map.Entry<String, Long> e : o.totals().entrySet())
        {
            if(!first)
                sb.append(" ");
            first = false;
            price(sb, e.getKey(), e.getValue(), true);
        }
        sb.append("</td></tr></table><table class=\"oi\">");
        for(int i = 0; i < Math.min(maxItems, o.items.size()); i++)
        {
            Orders.Item it = o.items.get(i);
            sb.append("<tr><td class=\"ic\"><span class=\"i24\" data-yr-item=\"").append(attr(it.icon)).append("\"></span></td><td>");
            name(sb, it.name, it.sortName, it.icon);
            if(it.count > 1)
                sb.append(" <span class=\"cnt\">&#215;").append(it.count).append("</span>");
            sb.append("</td><td class=\"q\">").append(it.quantity).append(" 份</td><td class=\"r\">");
            price(sb, it.currency, it.cost, false);
            sb.append("</td></tr>");
        }
        sb.append("</table>");
        if(o.items.size() > maxItems)
            sb.append("<div class=\"more\">还有 ").append(o.items.size() - maxItems).append(" 种商品，<a href=\"/order?no=").append(o.number).append("\">看整张订单</a></div>");
        sb.append("</div>\n");
    }

    // ---------------------------------------------------------------- 帮助、出错

    public static String help(Viewer v)
    {
        StringBuilder sb = new StringBuilder(8 * 1024);
        begin(sb, "帮助 - MineBay", v, "", null);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <b>帮助</b></div>\n<h2>帮助</h2>\n");
        sb.append("<div class=\"box\"><div class=\"boxh\">怎么买</div><div class=\"boxb\"><ol>");
        sb.append("<li>用游戏角色登录。</li>");
        sb.append("<li>加入购物车，点“去结算”；或在商品页点“立即购买”。</li>");
        sb.append("<li>提交订单（要同意提供电脑的位置），到 pay.yr 付款。</li>");
        sb.append("<li>付款后东西放进背包，满了掉在脚边。</li>");
        sb.append("</ol></div></div>\n");
        sb.append("<div class=\"box\"><div class=\"boxh\">要知道的</div><div class=\"boxb\"><ul>");
        sb.append("<li>买东西、收货的是登录的角色：他要在线、在电脑 8 格内。电脑要装定位模块。</li>");
        sb.append("<li>绿宝石价可以用悠然元付（1 绿宝石 = 2 元），也可以用 pay.yr 余额。</li>");
        sb.append("<li>限量商品全服共用库存，卖完要等补货。</li>");
        sb.append("<li>登录跟着电脑走，用完请退出。购物车、订单存在服务器上，换电脑也还在。</li>");
        sb.append("</ul></div></div>\n");
        end(sb);
        return sb.toString();
    }

    /** 客服页默认显示最近几条（多的点“全部记录”看） */
    public static final int SUPPORT_RECENT = 8;

    /**
     * 在线客服页（服主 11-15：老式网站的“在线客服”）：上面对话记录，下面输入框 +“发送”；客服正在回复时显示“小 Bay 正在输入……”并每 2 秒自动刷新。
     * @param available 客服开着没有（没配大模型时写“暂不可用”，没有输入框）
     * @param waiting   正在等客服回复
     * @param typed     玩家填的（发送失败时原样填回去）
     * @param all       显示全部记录
     */
    public static String support(Viewer v, boolean available, List<SupportChat.Message> messages, boolean waiting, @Nullable Notice notice, String typed,
                                 int perHour, boolean all)
    {
        StringBuilder sb = new StringBuilder(16 * 1024);
        begin(sb, "在线客服 - MineBay", v, "", null, waiting ? 2 : 0);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <b>在线客服</b></div>\n");
        sb.append("<table class=\"kfhead\"><tr><td><b class=\"kft\">MineBay 在线客服</b> <span class=\"dim small\">客服：").append(SupportPrompt.AGENT_NAME)
                .append("</span></td><td class=\"r\"><span class=\"kfon\">").append(available ? "&#9679; 在线" : "&#9675; 离线").append("</span></td></tr></table>\n");
        notice(sb, notice);
        if(v.player == null)
        {
            sb.append("<div class=\"empty\">登录后才能联系客服（对话记录跟着帐号走）。");
            if(v.loginAvailable)
                sb.append("<form method=\"get\" action=\"/login\"><input type=\"hidden\" name=\"next\" value=\"/support\"/><button type=\"submit\" class=\"btn buy big\">用游戏角色登录</button></form>");
            sb.append("<br/><a href=\"/help\">先看看帮助</a></div>\n");
            end(sb);
            return sb.toString();
        }
        sb.append("<div class=\"kflog\">\n");
        int from = all ? 0 : Math.max(0, messages.size() - SUPPORT_RECENT);
        if(from > 0)
            sb.append("<div class=\"kfmore\"><a href=\"/support?all=1\">显示全部记录（").append(messages.size()).append(" 条）</a></div>\n");
        if(messages.isEmpty())
            agent(sb, "亲，您好！我是 MineBay 在线客服" + SupportPrompt.AGENT_NAME + "，请问有什么可以帮您？", -1);
        for(int i = from; i < messages.size(); i++)
        {
            SupportChat.Message m = messages.get(i);
            if(m.role == SupportChat.Role.PLAYER)
                sb.append("<div class=\"kfmsg\"><span class=\"kfme\">").append(Html.escape(v.player)).append("</span> <span class=\"dim small\">")
                        .append(TIME.format(Instant.ofEpochMilli(m.time))).append("</span><div class=\"kftext\">").append(Html.escape(m.text)).append("</div></div>\n");
            else if(m.role == SupportChat.Role.AGENT)
                agent(sb, m.text, m.time);
            else
                sb.append("<div class=\"kfnote\">").append(Html.escape(m.text)).append("</div>\n");
        }
        if(waiting)
            sb.append("<div class=\"kftyping\">").append(SupportPrompt.AGENT_NAME).append(" 正在输入&#8230;&#8230;</div>\n");
        sb.append("</div>\n");
        if(!available)
        {
            sb.append("<div class=\"empty\">在线客服暂不可用。有问题请先看<a href=\"/help\">帮助</a>，或联系服主。</div>\n");
            end(sb);
            return sb.toString();
        }
        sb.append("<form method=\"post\" action=\"/support\" class=\"kfform\"><input type=\"hidden\" name=\"t\" value=\"").append(attr(v.token))
                .append("\"/><input type=\"text\" class=\"kfin\" name=\"m\" maxlength=\"").append(SupportText.MAX_MESSAGE).append("\" value=\"").append(attr(typed))
                .append("\"/> <button type=\"submit\" class=\"btn go\">发送</button></form>\n");
        sb.append("<p class=\"dim small\">").append(SupportPrompt.AGENT_NAME).append("只能解答问题，不能退款、改订单。每小时最多问 ").append(perHour)
                .append(" 条，一条最多 ").append(SupportText.MAX_MESSAGE).append(" 个字。</p>\n");
        end(sb);
        return sb.toString();
    }

    private static void agent(StringBuilder sb, String text, long time)
    {
        sb.append("<div class=\"kfmsg\"><span class=\"kfbay\">").append(SupportPrompt.AGENT_NAME).append("</span>");
        if(time >= 0)
            sb.append(" <span class=\"dim small\">").append(TIME.format(Instant.ofEpochMilli(time))).append("</span>");
        sb.append("<div class=\"kftext\">");
        String[] lines = text.split("\n");
        for(int i = 0; i < lines.length; i++)
        {
            if(i > 0)
                sb.append("<br/>");
            sb.append(Html.escape(lines[i]));
        }
        sb.append("</div></div>\n");
    }

    /** 出错页（参数不对、找不到商品……） */
    public static String problem(Viewer v, String title, String text)
    {
        StringBuilder sb = new StringBuilder(4 * 1024);
        begin(sb, title + " - MineBay", v, "", null);
        sb.append("<div class=\"crumbs\"><a href=\"/\">MineBay</a> &#8250; <b>").append(Html.escape(title)).append("</b></div>\n");
        sb.append("<div class=\"empty\"><b>").append(Html.escape(title)).append("</b><br/>").append(Html.escape(text)).append("<br/><a href=\"/\">回到首页</a></div>\n");
        end(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 零件

    public static String listUrl(@Nullable String category, String query, ShopParams.Sort sort, int page)
    {
        List<Map.Entry<String, String>> f = new ArrayList<>();
        if(!query.isEmpty())
            f.add(FormEncoding.field("q", query));
        if(category != null)
            f.add(FormEncoding.field("c", category));
        if(sort != ShopParams.Sort.DEFAULT)
            f.add(FormEncoding.field("s", sort.code));
        if(page > 1)
            f.add(FormEncoding.field("p", Integer.toString(page)));
        return f.isEmpty() ? "/" : "/?" + FormEncoding.encode(f);
    }

    public static String itemUrl(String id)
    {
        return "/item?" + FormEncoding.encode(Collections.singletonList(FormEncoding.field("id", id)));
    }

    private static String attr(String s)
    {
        return Html.escape(s);
    }

    static String clip(String s, int max)
    {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    private static void name(StringBuilder sb, @Nullable String name, String fallback, String icon)
    {
        if(name != null)
            sb.append(Html.escape(name));
        else
            sb.append("<span data-yr-item-name=\"").append(attr(icon)).append("\">").append(Html.escape(fallback)).append("</span>");
    }

    private static void nameLink(StringBuilder sb, @Nullable String name, String fallback, String icon, String url)
    {
        sb.append("<a href=\"").append(attr(url)).append("\"");
        if(name == null)
            sb.append(" data-yr-item-name=\"").append(attr(icon)).append("\">").append(Html.escape(fallback));
        else
            sb.append(">").append(Html.escape(name));
        sb.append("</a>");
    }

    /** 绿宝石（pay.yr 按 1 绿宝石 = 2 元收悠然元） */
    static final String EMERALD = "minecraft:emerald";

    /** 价格：货币图标 + 数量；绿宝石价同时写出悠然元（N 绿宝石 / 2N 元） */
    private static void price(StringBuilder sb, String currency, long amount, boolean strong)
    {
        sb.append("<span class=\"price\"><span class=\"c16\" data-yr-item=\"").append(attr(currency)).append("\"></span>").append(strong ? "<b>" : "")
                .append(amount).append(strong ? "</b>" : "");
        if(EMERALD.equals(currency))
            sb.append(" <span class=\"yuan\">/ ").append(amount * 2).append(" 元</span>");
        sb.append("</span>");
    }

    private static void totals(StringBuilder sb, Map<String, Long> totals)
    {
        for(Map.Entry<String, Long> e : totals.entrySet())
        {
            sb.append("<div class=\"totv\"><span class=\"c16\" data-yr-item=\"").append(attr(e.getKey())).append("\"></span><span class=\"cn\" data-yr-item-name=\"").append(attr(e.getKey())).append("\">")
                    .append(Html.escape(e.getKey())).append("</span> <b>").append(e.getValue()).append("</b>");
            if(EMERALD.equals(e.getKey()))
                sb.append(" <span class=\"yuan\">/ ").append(e.getValue() * 2).append(" 元</span>");
            sb.append("</div>");
        }
    }

    private static void pager(StringBuilder sb, int p, int pages, Function<Integer, String> url)
    {
        if(pages <= 1)
            return;
        sb.append("<div class=\"pager\">");
        if(p > 1)
            sb.append("<a class=\"pg\" href=\"").append(attr(url.apply(p - 1))).append("\">&#171; 上一页</a> ");
        int from = Math.max(1, p - 4);
        int to = Math.min(pages, from + 8);
        from = Math.max(1, to - 8);
        if(from > 1)
            sb.append("<a class=\"pg\" href=\"").append(attr(url.apply(1))).append("\">1</a> <span class=\"dim\">&#8230;</span> ");
        for(int i = from; i <= to; i++)
        {
            if(i == p)
                sb.append("<span class=\"pg cur\">").append(i).append("</span> ");
            else
                sb.append("<a class=\"pg\" href=\"").append(attr(url.apply(i))).append("\">").append(i).append("</a> ");
        }
        if(to < pages)
            sb.append("<span class=\"dim\">&#8230;</span> <a class=\"pg\" href=\"").append(attr(url.apply(pages))).append("\">").append(pages).append("</a> ");
        if(p < pages)
            sb.append("<a class=\"pg\" href=\"").append(attr(url.apply(p + 1))).append("\">下一页 &#187;</a>");
        sb.append(" <span class=\"dim\">第 ").append(p).append(" / ").append(pages).append(" 页</span></div>\n");
    }

    private static void notice(StringBuilder sb, @Nullable Notice n)
    {
        if(n != null && !n.text.isEmpty())
            sb.append("<div class=\"notice ").append(n.ok ? "ok" : "bad").append("\">").append(Html.escape(n.text)).append("</div>\n");
    }

    /**
     * 页头：顶条、标志 + 搜索 + 购物车、分类导航。
     * @param navActive 导航里高亮哪一项：空串是“全部商品”，null 不高亮
     */
    private static void begin(StringBuilder sb, String title, Viewer v, String query, @Nullable String navActive)
    {
        begin(sb, title, v, query, navActive, 0);
    }

    /** @param refreshSeconds 大于 0 时页面过这么多秒自动刷新（{@code <meta http-equiv="refresh">}；浏览器在玩家填着表单时不刷） */
    private static void begin(StringBuilder sb, String title, Viewer v, String query, @Nullable String navActive, int refreshSeconds)
    {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\"><head><title>").append(Html.escape(title)).append("</title>\n");
        if(refreshSeconds > 0)
            sb.append("<meta http-equiv=\"refresh\" content=\"").append(refreshSeconds).append("\"/>\n");
        sb.append("<style type=\"text/css\">\n").append(CSS).append("</style></head><body>\n");
        sb.append("<div id=\"topbar\"><span class=\"links\"><a href=\"/orders\">我的订单</a> <span class=\"sep\">|</span> <a href=\"/help\">帮助</a> <span class=\"sep\">|</span> <a href=\"/support\" class=\"kf\">联系客服</a></span>");
        if(v.player != null)
            sb.append("你好，<b>").append(Html.escape(v.player)).append("</b> <form method=\"post\" action=\"/logout\" class=\"inl\"><input type=\"hidden\" name=\"t\" value=\"")
                    .append(attr(v.token)).append("\"/><button type=\"submit\" class=\"lnk\">退出</button></form>");
        else if(v.loginAvailable)
            sb.append("你好！<a href=\"/login\">请登录</a>");
        else
            sb.append("你好！");
        sb.append("</div>\n");
        sb.append("<table id=\"head\"><tr><td class=\"logo\"><a href=\"/\" class=\"logo\"><span class=\"l1\">M</span><span class=\"l2\">i</span><span class=\"l3\">n</span><span class=\"l4\">e</span>")
                .append("<span class=\"l5\">B</span><span class=\"l6\">a</span><span class=\"l7\">y</span></a><div class=\"tag\">方块世界的网上商城</div></td>");
        sb.append("<td class=\"search\"><form method=\"get\" action=\"/\"><input type=\"text\" class=\"q\" name=\"q\" maxlength=\"").append(ShopParams.MAX_QUERY)
                .append("\" value=\"").append(attr(query)).append("\"/><button type=\"submit\" class=\"btn go\">搜索</button></form></td>");
        sb.append("<td class=\"cartcell\"><a href=\"/cart\" class=\"cartbox\">购物车 <b class=\"n\">").append(v.player == null ? "-" : Integer.toString(v.cartCount)).append("</b> 件</a></td></tr></table>\n");
        sb.append("<div id=\"nav\">").append(navLink("/", "全部商品", "".equals(navActive)));
        for(Categories.Category c : Categories.all())
            sb.append(navLink(listUrl(c.id, "", ShopParams.Sort.DEFAULT, 1), c.name, c.id.equals(navActive)));
        sb.append("</div>\n<div id=\"main\">\n");
    }

    private static String navLink(String url, String label, boolean on)
    {
        return "<a href=\"" + attr(url) + "\"" + (on ? " class=\"on\"" : "") + ">" + Html.escape(label) + "</a>";
    }

    private static void end(StringBuilder sb)
    {
        sb.append("</div>\n<div id=\"foot\"><div><a href=\"/\">首页</a> <span class=\"sep\">|</span> <a href=\"/cart\">购物车</a> <span class=\"sep\">|</span> <a href=\"/orders\">我的订单</a> <span class=\"sep\">|</span> <a href=\"/help\">帮助</a> <span class=\"sep\">|</span> <a href=\"/support\">联系客服</a></div>");
        sb.append("<div class=\"dim\">MineBay 是悠然服务器内网上的商城（原是 MrCrayfish 家具模组电脑里的商店）。价格以结算时为准。</div></div>\n");
        sb.append("</body></html>\n");
    }

    static final String CSS =
            "body { margin: 0; background: #ffffff; color: #333; font-size: 13px; }\n"
            + "a { color: #0654ba; text-decoration: none; }\n"
            + ".dim { color: #888; } .small { font-size: 11px; } .sep { color: #ccc; } .r { text-align: right; }\n"
            + "#topbar { background: #f4f4f4; border-bottom: 1px solid #e2e2e2; padding: 3px 10px; font-size: 12px; color: #555; }\n"
            + "#topbar .links { float: right; }\n"
            + "#head { width: 100%; border-collapse: collapse; margin: 6px 0 4px 0; }\n"
            + "#head td { vertical-align: middle; padding: 0 10px; }\n"
            + "td.logo { width: 128px; } a.logo { font-size: 30px; font-weight: bold; letter-spacing: -2px; }\n"
            + ".l1, .l5 { color: #e53238; } .l2, .l6 { color: #0064d2; } .l3, .l7 { color: #f5af02; } .l4 { color: #86b817; }\n"
            + ".tag { font-size: 11px; color: #777; }\n"
            + "input.q { width: 230px; height: 20px; border: 2px solid #3665f3; padding: 2px 4px; }\n"
            + ".btn { display: inline-block; padding: 3px 10px; border: 1px solid #2a50c8; background: #3665f3; color: #fff; font-weight: bold; border-radius: 3px; }\n"
            + ".btn.go { padding: 5px 14px; margin-left: 2px; }\n"
            + "td.cartcell { width: 100px; text-align: right; }\n"
            + "a.cartbox { display: inline-block; border: 1px solid #f0c14b; background: #fff8e1; color: #333; padding: 5px 8px; border-radius: 3px; font-size: 12px; }\n"
            + "a.cartbox b.n { color: #e53238; font-size: 14px; }\n"
            + "#nav { background: #2a50c8; border-bottom: 3px solid #f5af02; padding: 0 6px; }\n"
            + "#nav a { display: inline-block; color: #fff; padding: 5px 9px; }\n"
            + "#nav a.on { background: #fff; color: #2a50c8; font-weight: bold; }\n"
            + "#main { padding: 6px 10px 10px 10px; }\n"
            + ".crumbs { font-size: 12px; color: #666; margin: 2px 0 8px 0; }\n"
            + "h1 { font-size: 18px; margin: 0 0 8px 0; color: #222; }\n"
            + "h2 { font-size: 16px; margin: 4px 0 8px 0; color: #222; border-bottom: 2px solid #e5e5e5; padding-bottom: 3px; }\n"
            + ".notice { padding: 7px 10px; margin: 0 0 8px 0; border: 1px solid #ccc; border-radius: 3px; }\n"
            + ".notice.ok { background: #e9f7df; border-color: #8cc56b; color: #2b542c; } .notice.bad { background: #fdecea; border-color: #e6a19b; color: #a42a20; }\n"
            + ".banner { border: 1px solid #f0c14b; background: #fffbea; padding: 8px 10px; margin-bottom: 8px; border-radius: 4px; }\n"
            + ".banner .bt { font-size: 17px; font-weight: bold; color: #e53238; } .banner .bd { color: #555; margin: 2px 0 6px 0; }\n"
            + "table.perks { width: 100%; border-collapse: collapse; } table.perks td { width: 33%; font-size: 12px; color: #666; border-left: 3px solid #86b817; padding: 1px 6px; }\n"
            + ".facets { font-size: 12px; margin-bottom: 6px; }\n"
            + "table.bar { width: 100%; border-collapse: collapse; background: #f6f6f6; border: 1px solid #e2e2e2; margin-bottom: 4px; font-size: 12px; }\n"
            + "table.bar td { padding: 4px 8px; }\n"
            + "table.grid { width: 100%; border-collapse: separate; border-spacing: 0 6px; table-layout: fixed; }\n"
            + "td.cell { width: 25%; vertical-align: top; padding: 0 3px; }\n"
            + ".card { border: 1px solid #ddd; background: #fff; padding: 6px; text-align: center; height: 146px; }\n"
            + ".pic { height: 52px; } a.i48 { display: inline-block; width: 48px; height: 48px; }\n"
            + ".nm { height: 32px; font-size: 12px; line-height: 16px; overflow: hidden; } .cnt { color: #888; font-size: 11px; }\n"
            + ".pr { font-size: 15px; color: #e53238; font-weight: bold; margin: 2px 0 6px 0; }\n"
            + ".price { white-space: nowrap; } .c16 { display: inline-block; width: 16px; height: 16px; vertical-align: middle; margin-right: 2px; }\n"
            + "form { margin: 0; }\n"
            + ".btn.add { background: #f5af02; border-color: #d39500; color: #222; font-size: 12px; }\n"
            + ".btn.buy { background: #e53238; border-color: #b8262b; color: #fff; }\n"
            + ".btn.big { font-size: 14px; padding: 5px 16px; } .btn.small { font-size: 11px; padding: 1px 6px; font-weight: normal; }\n"
            + ".btn.del { background: #fff; color: #a42a20; border-color: #d9a29d; }\n"
            + ".pager { text-align: center; margin: 6px 0; font-size: 12px; }\n"
            + ".pg { display: inline-block; border: 1px solid #ccc; padding: 2px 7px; margin: 0 1px; }\n"
            + ".pg.cur { background: #2a50c8; border-color: #2a50c8; color: #fff; font-weight: bold; }\n"
            + ".empty { border: 1px dashed #ccc; padding: 24px; text-align: center; color: #666; line-height: 22px; }\n"
            + "table.detail { width: 100%; border-collapse: collapse; margin-bottom: 10px; } table.detail td { vertical-align: top; }\n"
            + "td.big { width: 130px; text-align: center; } .bigbox { border: 1px solid #ddd; width: 120px; height: 120px; margin: 0 auto 4px auto; }\n"
            + ".i96 { display: inline-block; width: 96px; height: 96px; margin-top: 12px; }\n"
            + "td.info { padding-left: 14px; }\n"
            + "table.facts { border-collapse: collapse; margin-bottom: 8px; } table.facts th { text-align: left; color: #888; font-weight: normal; padding: 3px 12px 3px 0; } table.facts td { padding: 3px 0; }\n"
            + ".bigprice { font-size: 22px; color: #e53238; font-weight: bold; }\n"
            + ".buybox { background: #f7f7f7; border: 1px solid #e2e2e2; padding: 8px 10px; }\n"
            + ".qtyline { margin-bottom: 8px; } input.qty { width: 30px; height: 18px; text-align: right; }\n"
            + ".incart { margin-top: 6px; font-size: 12px; color: #2b542c; }\n"
            + ".box { border: 1px solid #ddd; margin-bottom: 10px; } .boxh { background: #f0f0f0; border-bottom: 1px solid #ddd; padding: 4px 8px; font-weight: bold; }\n"
            + ".boxb { padding: 4px 10px; } .boxb ol, .boxb ul { margin: 4px 0; padding-left: 20px; } .boxb li { margin: 3px 0; }\n"
            + "table.lines { width: 100%; border-collapse: collapse; border: 1px solid #ddd; }\n"
            + "table.lines th { background: #f0f0f0; text-align: left; font-weight: normal; color: #666; padding: 4px 6px; border-bottom: 1px solid #ddd; font-size: 12px; }\n"
            + "table.lines td { padding: 5px 6px; border-bottom: 1px solid #eee; vertical-align: middle; }\n"
            + "td.ic { width: 34px; } a.i32, span.i32 { display: inline-block; width: 32px; height: 32px; } span.gone { background: #eee; }\n"
            + "td.up, th.q, td.q { white-space: nowrap; } th.r, td.r { text-align: right; white-space: nowrap; }\n"
            + "tr.off td { background: #fafafa; color: #999; } .warn { color: #c45500; font-size: 12px; }\n"
            + "table.sum { width: 100%; border-collapse: collapse; margin-top: 8px; } table.sum td { vertical-align: top; }\n"
            + "td.note { font-size: 12px; color: #888; padding-right: 10px; line-height: 18px; }\n"
            + "td.tot { width: 200px; text-align: right; background: #fffbea; border: 1px solid #f0c14b; padding: 8px; }\n"
            + ".totl { color: #666; } .totv { font-size: 15px; color: #e53238; margin: 2px 0; } .totv .cn { color: #555; font-size: 12px; margin-right: 4px; } .go { margin-top: 6px; }\n"
            + "table.steps { width: 100%; border-collapse: collapse; margin-bottom: 8px; } table.steps td { width: 25%; text-align: center; padding: 4px; background: #eee; color: #999; border: 2px solid #fff; }\n"
            + "table.steps td.done { background: #dbe6fb; color: #2a50c8; } table.steps td.on { background: #2a50c8; color: #fff; font-weight: bold; }\n"
            + ".paybox { margin-top: 10px; } table.pay { width: 100%; border-collapse: collapse; } table.pay th { text-align: left; font-weight: normal; color: #888; padding: 3px 4px; }\n"
            + "table.pay td { padding: 3px 4px; border-top: 1px solid #eee; }\n"
            + ".good { color: #2b8a3e; } .bad { color: #c62828; font-weight: bold; }\n"
            + ".stock { font-size: 11px; color: #2b8a3e; font-weight: normal; } .stock.out { color: #c62828; } .yuan { color: #888; font-weight: normal; font-size: 11px; }\n"
            + ".btn.off { background: #ccc; border-color: #aaa; color: #fff; } form.inl { display: inline; } button.lnk { border: 0; background: transparent; color: #0654ba; padding: 0; font-size: 12px; }\n"
            + ".confirm { border: 1px solid #f0c14b; background: #fffbea; padding: 8px 10px; }\n"
            + ".order { border: 1px solid #ddd; margin-bottom: 8px; } .order.new { border: 2px solid #86b817; }\n"
            + "table.oh { width: 100%; border-collapse: collapse; background: #f0f0f0; font-size: 12px; } table.oh td { padding: 4px 8px; }\n"
            + "table.oi { width: 100%; border-collapse: collapse; } table.oi td { padding: 3px 8px; border-top: 1px solid #f0f0f0; vertical-align: middle; }\n"
            + ".i24 { display: inline-block; width: 24px; height: 24px; }\n"
            + ".more { padding: 3px 8px; font-size: 12px; background: #fafafa; }\n"
            + "table.kfhead { width: 100%; border-collapse: collapse; background: #eef3fd; border: 1px solid #c8d6f5; margin-bottom: 6px; } table.kfhead td { padding: 5px 8px; }\n"
            + ".kft { color: #2a50c8; font-size: 15px; } .kfon { color: #2b8a3e; font-size: 12px; } a.kf { color: #e53238; font-weight: bold; }\n"
            + ".kflog { border: 1px solid #ccc; background: #fbfbfb; padding: 6px 8px; margin-bottom: 6px; }\n"
            + ".kfmsg { margin: 4px 0 8px 0; } .kfme { color: #0654ba; font-weight: bold; } .kfbay { color: #e53238; font-weight: bold; }\n"
            + ".kftext { padding: 2px 0 0 12px; line-height: 18px; } .kfnote { color: #888; font-size: 12px; margin: 4px 0; }\n"
            + ".kftyping { color: #c45500; font-size: 12px; margin: 4px 0; } .kfmore { text-align: center; font-size: 12px; margin-bottom: 4px; }\n"
            + "input.kfin { width: 380px; height: 20px; }\n"
            + "#foot { border-top: 1px solid #ddd; background: #f4f4f4; padding: 8px 10px; font-size: 12px; text-align: center; color: #666; line-height: 20px; }\n";
}
