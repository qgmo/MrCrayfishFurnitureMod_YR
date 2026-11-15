package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.client.browser.PageDocument;
import com.mrcrayfish.furniture.computer.PageLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MineBayPagesTest
{
    static final MineBayPages.Viewer VIEWER = new MineBayPages.Viewer("Steve", 3, "abc");

    static Listing listing(int i, String category, String currency, long price)
    {
        List<List<Listing.Part>> desc = new ArrayList<>();
        desc.add(Arrays.asList(Listing.Part.lang("enchantment.untouching", "Silk Touch"), Listing.Part.text(" I")));
        return new Listing("config:" + Integer.toHexString(0x100000 + i), i, category, "minecraft:dye@" + (i % 16), 1 + i % 3, i % 7 == 0 ? "改名的 <物品> " + i : null,
                "染料 " + i, "染料 dye " + i, currency, price, 64, desc);
    }

    static List<Listing> many(int n)
    {
        List<Listing> list = new ArrayList<>();
        for(int i = 0; i < n; i++)
            list.add(listing(i, i % 2 == 0 ? Categories.BLOCKS : Categories.MISC, i % 3 == 0 ? "minecraft:diamond" : "minecraft:emerald", 1 + i % 10));
        return list;
    }

    static PageDocument check(String page) throws Exception
    {
        byte[] html = page.getBytes(StandardCharsets.UTF_8);
        assertNull(PageLimits.check(html), "页面超限");
        return PageDocument.parse(html, "https://minebay.yr/", id -> null, 600);
    }

    @Test
    void shopPageIsValidAndWithinLimits() throws Exception
    {
        List<Listing> list = many(300);
        for(int page = 1; page <= 3; page++)
        {
            PageDocument d = check(MineBayPages.list(VIEWER, list, list, null, "", ShopParams.Sort.DEFAULT, page, page == 2 ? MineBayPages.Notice.ok("买好了 <ok>") : null));
            // 顶栏“退出”1 个（登录了）+ 搜索框 1 个 + 每件商品一个“加入购物车”
            assertEquals(2 + ShopParams.PER_PAGE, d.forms.size());
            assertEquals("https://minebay.yr/logout", d.forms.get(0).action);
            assertTrue(d.forms.get(0).post);
            assertEquals("https://minebay.yr/cart/add", d.forms.get(2).action);
            assertTrue(d.forms.get(2).post);
            assertFalse(d.forms.get(1).post, "搜索用 GET");
            assertEquals(ShopParams.PER_PAGE + 1, d.controls.stream().filter(c -> c.kind == PageDocument.Kind.HIDDEN && c.name.equals("t") && c.value.equals("abc")).count(), "每张 POST 表单带令牌");
        }
        // 页码超出范围时停在最后一页
        assertTrue(MineBayPages.list(VIEWER, list, list, null, "", ShopParams.Sort.DEFAULT, 999, null).contains("第 25 / 25 页"));
    }

    @Test
    void searchAndCategoryLinksAreEncoded() throws Exception
    {
        List<Listing> list = many(20);
        String q = "a&b <x> \"y\"";
        PageDocument d = check(MineBayPages.list(VIEWER, list, list, null, q, ShopParams.Sort.PRICE_DESC, 1, null));
        assertTrue(d.links.containsValue("https://minebay.yr/?q=a%26b+%3Cx%3E+%22y%22&c=blocks&s=price_desc"), d.links.values().toString());
        assertTrue(d.links.containsValue("https://minebay.yr/?c=misc"));
        assertTrue(d.links.containsValue("https://minebay.yr/item?id=config%3A100001"));
        // 搜索框里填回原词
        assertEquals(q, d.controls.stream().filter(c -> c.name.equals("q")).findFirst().get().value);
        // 没找到
        check(MineBayPages.list(VIEWER, list, Collections.<Listing>emptyList(), "misc", "", ShopParams.Sort.NAME, 1, null));
    }

    @Test
    void itemPageRefillsTypedQuantity() throws Exception
    {
        Listing l = listing(7, Categories.MAGIC, "minecraft:emerald", 8);
        PageDocument d = check(MineBayPages.item(VIEWER, l, 2, MineBayPages.Notice.bad("数量不对"), "9x9"));
        PageDocument.Control n = d.controls.stream().filter(c -> c.name.equals("n")).findFirst().get();
        assertEquals("9x9", n.value);
        assertEquals(2, d.controls.stream().filter(c -> c.kind == PageDocument.Kind.SUBMIT && c.name.equals("go")).count(), "加入购物车、立即购买");
        String html = MineBayPages.item(VIEWER, l, 0, null, null);
        assertTrue(html.contains("data-yr-lang=\"enchantment.untouching\""));
        assertTrue(html.contains("改名的 &lt;物品&gt; 7"), "网站给的名字要转义");
    }

    @Test
    void cartCheckoutOrdersPagesAtTheLimits() throws Exception
    {
        List<Listing> list = many(40);
        Map<String, Listing> by = new HashMap<>();
        for(Listing l : list)
            by.put(l.id, l);
        Function<String, Listing> lookup = by::get;
        Cart cart = new Cart();
        for(int i = 0; i < Cart.MAX_LINES - 1; i++)
            assertEquals(Cart.Change.OK, cart.add(list.get(i).id, Cart.MAX_QUANTITY, 64));
        assertEquals(Cart.Change.OK, cart.add("config:gone", 1, 64));
        Checkout.Plan plan = Checkout.plan(cart.lines(), lookup);
        assertEquals(1, plan.unavailable.size());
        PageDocument d = check(MineBayPages.cart(VIEWER, cart, lookup, plan, null, Collections.singletonMap(list.get(0).id, "abc")));
        assertEquals("abc", d.controls.stream().filter(c -> c.name.equals("n")).findFirst().get().value, "没提交成功的数量原样填回去");
        // 改接 pay.yr 后结算页不再数背包；没有确认码时没有提交按钮
        String none = MineBayPages.checkout(VIEWER, plan, lookup, null, null, null, null);
        check(none);
        assertFalse(none.contains("action=\"/checkout\""), "没有确认码时没有提交按钮");
        assertFalse(none.contains("背包里有"), "不再显示背包里有多少");
        String ok = MineBayPages.checkout(VIEWER, plan, lookup, "nonce1", "locnonce-123456", null, null);
        PageDocument okDoc = check(ok);
        assertTrue(okDoc.controls.stream().anyMatch(c -> c.name.equals("k") && c.value.equals("nonce1")));
        assertTrue(ok.contains("type=\"x-yr-location\" name=\"loc\" value=\"locnonce-123456\""), "提交时带上定位模块签名的位置");
        assertTrue(ok.contains("pay.yr"));
        // 订单：每页 5 单，每单 32 种
        List<Orders.Order> orders = new ArrayList<>();
        for(int o = 0; o < Orders.MAX_ORDERS; o++)
        {
            List<Orders.Item> items = new ArrayList<>();
            for(int i = 0; i < Cart.MAX_LINES; i++)
                items.add(new Orders.Item(list.get(i).id, list.get(i).icon, 64, i % 2 == 0 ? null : "名字很长很长很长很长很长很长很长很长很长很长很长很长", "染料", 64, "minecraft:emerald", 1_000_000L));
            orders.add(new Orders.Order(1000 - o, 1_700_000_000_000L, items));
        }
        for(int p = 1; p <= 10; p++)
            check(MineBayPages.orders(VIEWER, orders, p, MineBayPages.Notice.ok("下单成功"), 1000));
        check(MineBayPages.orderDetail(VIEWER, orders.get(0)));
        check(MineBayPages.removeConfirm(VIEWER, list.get(0).id, list.get(0), 3));
        check(MineBayPages.help(VIEWER));
        check(MineBayPages.problem(new MineBayPages.Viewer(null, 0, "t"), "出错 <了>", "说明 & 原因"));
    }

    /** 绿宝石价同时写出悠然元（N 绿宝石 / 2N 元）；别的货币不写 */
    @Test
    void emeraldPricesShowYuan()
    {
        String e = MineBayPages.item(VIEWER, listing(1, Categories.MISC, "minecraft:emerald", 16), 0, null, null);
        assertTrue(e.contains("/ 32 元"), "16 绿宝石 / 32 元");
        String d = MineBayPages.item(VIEWER, listing(1, Categories.MISC, "minecraft:diamond", 3), 0, null, null);
        assertFalse(d.contains(" 元</span>"), "钻石价不换算");
    }

    static MineBayPages.Viewer stockViewer(long left)
    {
        return new MineBayPages.Viewer("Steve", 0, "abc", new MineBayPages.StockView()
        {
            public long remaining(Listing l)
            {
                return l.stock == null ? -1 : left;
            }

            public String refill(Listing l)
            {
                return "约 2 天后补 1 份";
            }
        }, true);
    }

    static Listing limited(int i, Stock.Rule rule)
    {
        Listing l = listing(i, Categories.MISC, "minecraft:emerald", 16);
        return new Listing(l.id, l.seq, l.category, l.icon, l.count, "BASIC 盘", l.sortName, l.searchText, l.currency, l.price, 4, l.description, new Stock.Spec(14, rule));
    }

    /** 限量商品：卡片、详情、购物车、结算都显示剩余；卖完没有购买按钮 */
    @Test
    void stockShownEverywhere() throws Exception
    {
        Listing l = limited(1, Stock.Rule.DAILY);
        List<Listing> one = Collections.singletonList(l);
        String list5 = MineBayPages.list(stockViewer(5), one, one, null, "", ShopParams.Sort.DEFAULT, 1, null);
        assertTrue(list5.contains("剩 5 份"));
        assertEquals(3, check(list5).forms.size(), "退出 + 搜索 + 加入购物车");
        String list0 = MineBayPages.list(stockViewer(0), one, one, null, "", ShopParams.Sort.DEFAULT, 1, null);
        assertTrue(list0.contains("已卖完"));
        assertEquals(2, check(list0).forms.size(), "卖完了没有加入购物车");
        String item0 = MineBayPages.item(stockViewer(0), l, 0, null, null);
        assertTrue(item0.contains("每月 14 份，每天补") && item0.contains("约 2 天后补 1 份"));
        assertEquals(0, check(item0).controls.stream().filter(c -> c.kind == PageDocument.Kind.SUBMIT && "go".equals(c.name)).count(), "卖完了没有购买按钮");
        assertTrue(MineBayPages.item(stockViewer(3), limited(2, Stock.Rule.MONTHLY), 0, null, null).contains("每月 1 日补满"));
        Cart cart = new Cart();
        cart.add(l.id, 4, 4);
        Checkout.Plan plan = Checkout.plan(cart.lines(), id -> l, id -> 3);
        assertEquals(Checkout.Unavailable.STOCK, plan.unavailable.get(l.id));
        String c = MineBayPages.cart(stockViewer(3), cart, id -> l, plan, null, Collections.<String, String>emptyMap());
        check(c);
        assertTrue(c.contains("库存只剩 3 份"));
        Checkout.Plan ok = Checkout.plan(cart.lines(), id -> l, id -> 9);
        String co = MineBayPages.checkout(stockViewer(9), ok, id -> l, "k", "locnonce-1", null, null);
        check(co);
        assertTrue(co.contains("剩 9 份"));
    }

    /** 没登录：购物车、订单给登录按钮；服务器没开 id.yr 时说明 */
    /** 帐号设置页（“操作前必须验证身份”开关带令牌）、等验证结果页（自动刷新）、顶栏的“帐号设置”、帮助里的说明 */
    @Test
    void accountPages() throws Exception
    {
        PageDocument off = check(MineBayPages.account(VIEWER, false, null));
        PageDocument.FormInfo f = off.forms.stream().filter(x -> x.action.equals("https://minebay.yr/account/security")).findFirst().orElse(null);
        assertNotNull(f);
        assertTrue(f.post);
        assertTrue(off.controls.stream().anyMatch(c -> c.kind == PageDocument.Kind.HIDDEN && c.name.equals("verify") && c.value.equals("1")), "关着时按钮是打开");
        assertTrue(off.controls.stream().anyMatch(c -> c.kind == PageDocument.Kind.HIDDEN && c.name.equals("t") && c.value.equals("abc")));
        String on = MineBayPages.account(VIEWER, true, MineBayPages.Notice.ok("已打开"));
        check(on);
        assertTrue(on.contains("已打开</b>") && on.contains("name=\"verify\" value=\"0\""));
        String w = MineBayPages.waitVerify(VIEWER, "g_abc");
        check(w);
        assertTrue(w.contains("http-equiv=\"refresh\" content=\"2\""));
        assertTrue(w.contains("/verified?g=g_abc"));
        assertTrue(MineBayPages.help(VIEWER).contains("操作前必须验证身份"));
        assertTrue(MineBayPages.help(VIEWER).contains("href=\"/account\""), "登录了顶栏有帐号设置");
        assertFalse(MineBayPages.help(new MineBayPages.Viewer(null, 0, "t")).contains("href=\"/account\""));
    }

    @Test
    void loginPages() throws Exception
    {
        MineBayPages.Viewer anon = MineBayPages.Viewer.anonymous("t");
        PageDocument d = check(MineBayPages.loginNeeded(anon, "/cart"));
        assertTrue(d.forms.stream().anyMatch(f -> f.action.equals("https://minebay.yr/login")));
        assertTrue(d.controls.stream().anyMatch(c -> c.name.equals("next") && c.value.equals("/cart")));
        String off = MineBayPages.loginNeeded(new MineBayPages.Viewer(null, 0, "t", MineBayPages.NO_STOCK, false), "/cart");
        assertTrue(off.contains("id.yr") && !off.contains("action=\"/login\""));
        String in = MineBayPages.help(VIEWER);
        assertTrue(in.contains("action=\"/logout\""), "登录了有退出");
        check(MineBayPages.unpaid(VIEWER, "https://pay.yr/c/ord_aaaaaaaaaaaaaaaa"));
    }

    /** 在线客服页：合法、对话转义、等回复时自动刷新、没开时没有输入框、没登录时让登录、发送失败时填的原样填回去 */
    @Test
    void supportPage() throws Exception
    {
        List<SupportChat.Message> msgs = new ArrayList<>();
        for(int i = 0; i < 30; i++)
        {
            msgs.add(new SupportChat.Message(SupportChat.Role.PLAYER, "问 <b>" + i + "</b>", 1_763_200_000_000L + i));
            msgs.add(new SupportChat.Message(SupportChat.Role.AGENT, "答 " + i + "\n第二行 <script>", 1_763_200_000_000L + i));
        }
        msgs.add(new SupportChat.Message(SupportChat.Role.NOTE, "（已转给管理员，留言编号 #1。）", 1_763_200_000_000L));
        String html = MineBayPages.support(VIEWER, true, msgs, true, null, "", 20, false);
        PageDocument d = check(html);
        assertNotNull(d.refresh, "等回复时自动刷新");
        assertEquals(2, d.refresh.seconds);
        assertEquals("https://minebay.yr/", d.refresh.url, "刷新的是这一页自己");
        assertTrue(html.contains("小 Bay 正在输入"));
        assertTrue(html.contains("问 &lt;b&gt;29&lt;/b&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("答 29<br/>第二行"));
        assertFalse(html.contains("问 &lt;b&gt;20&lt;/b&gt;"), "默认只显示最近几条");
        assertTrue(html.contains("显示全部记录（61 条）"));
        assertFalse(html.contains("问 &lt;b&gt;26&lt;/b&gt;"), "默认只显示最近 " + MineBayPages.SUPPORT_RECENT + " 条");
        assertTrue(MineBayPages.list(VIEWER, many(3), many(3), null, "", ShopParams.Sort.DEFAULT, 1, null).contains("href=\"/support\" class=\"kf\">联系客服"),
                "商城页面角落的联系客服");
        assertTrue(html.contains("返回 MineBay"));
        assertTrue(d.forms.stream().anyMatch(f -> f.post && f.action.equals("https://minebay.yr/support")));

        String all = MineBayPages.support(VIEWER, true, msgs, false, MineBayPages.Notice.bad("每小时最多问 20 条"), "我填的 \"字\"", 20, true);
        PageDocument a = check(all);
        assertNull(a.refresh, "不等回复时不刷新");
        assertTrue(all.contains("问 &lt;b&gt;0&lt;/b&gt;"));
        assertTrue(a.controls.stream().anyMatch(c -> c.name.equals("m") && c.value.equals("我填的 \"字\"")), "填的原样填回去");

        String off = MineBayPages.support(VIEWER, false, Collections.<SupportChat.Message>emptyList(), false, null, "", 20, false);
        check(off);
        assertTrue(off.contains("在线客服暂不可用"));
        assertFalse(off.contains("action=\"/support\""));

        String anon = MineBayPages.support(MineBayPages.Viewer.anonymous("t"), true, Collections.<SupportChat.Message>emptyList(), false, null, "", 20, false);
        check(anon);
        assertTrue(anon.contains("登录后才能联系客服"));
        assertTrue(MineBayPages.support(VIEWER, true, Collections.<SupportChat.Message>emptyList(), false, null, "", 20, false).contains("请问有什么可以帮您"));
    }
}
