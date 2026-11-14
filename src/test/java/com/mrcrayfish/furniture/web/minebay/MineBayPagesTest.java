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
            // 搜索框 1 个 + 每件商品一个“加入购物车”
            assertEquals(1 + ShopParams.PER_PAGE, d.forms.size());
            assertEquals("https://minebay.yr/cart/add", d.forms.get(1).action);
            assertTrue(d.forms.get(1).post);
            assertFalse(d.forms.get(0).post, "搜索用 GET");
            assertEquals(ShopParams.PER_PAGE, d.controls.stream().filter(c -> c.kind == PageDocument.Kind.HIDDEN && c.name.equals("t") && c.value.equals("abc")).count(), "每张表单带令牌");
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
        Map<String, Long> have = new HashMap<>();
        have.put("minecraft:emerald", 5L);
        String poor = MineBayPages.checkout(VIEWER, plan, lookup, have, null, null, null);
        check(poor);
        assertFalse(poor.contains("action=\"/checkout\""), "钱不够时没有确认按钮");
        have.put("minecraft:emerald", 1_000_000L);
        have.put("minecraft:diamond", 1_000_000L);
        String ok = MineBayPages.checkout(VIEWER, plan, lookup, have, "nonce1", null, null);
        assertTrue(check(ok).controls.stream().anyMatch(c -> c.name.equals("k") && c.value.equals("nonce1")));
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
}
