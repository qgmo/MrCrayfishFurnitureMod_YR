package com.mrcrayfish.furniture.web.minebay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 浏览器排版检查用的 MineBay 页面（{@code client.browser.BrowserLayoutTest} 与排版检查工具用）：每种页面一份，
 * 名字、物品名、玩家名都挑长的（长中文、长英文单词、长网址），看会不会伸出盒子、按钮一不一样高。
 */
public final class MineBayLayoutSamples
{
    private MineBayLayoutSamples() {}

    static final String LONG_EN = "Supercalifragilisticexpialidocious_Enchanted_Diamond_Sword";
    static final String LONG_ZH = "超级无敌附魔钻石剑（锋利 V、耐久 III、经验修补、火焰附加 II）";

    static Listing listing(int i, String category, String currency, long price)
    {
        List<List<Listing.Part>> desc = new ArrayList<>();
        desc.add(Arrays.asList(Listing.Part.lang("enchantment.untouching", "Silk Touch"), Listing.Part.text(" I")));
        desc.add(Collections.singletonList(Listing.Part.text(i % 2 == 0 ? "说明：" + LONG_ZH + "，" + LONG_ZH : "https://averyveryverylongdomainnamewithoutanybreaks.yr/item/" + i)));
        String name = i % 3 == 0 ? LONG_ZH : i % 3 == 1 ? LONG_EN + "_" + i : "染料 " + i;
        return new Listing("config:" + Integer.toHexString(0x100000 + i), i, category, "minecraft:dye@" + (i % 16), 1 + i % 3, name,
                "染料 " + i, "染料 dye " + i, currency, price, 64, desc);
    }

    /** 名字 → 页面 */
    public static Map<String, String> pages()
    {
        Map<String, String> out = new LinkedHashMap<>();
        MineBayPages.Viewer v = new MineBayPages.Viewer("abcdefghijklmnop", 3, "abc");
        List<Listing> list = new ArrayList<>();
        for(int i = 0; i < 30; i++)
            list.add(listing(i, i % 2 == 0 ? Categories.BLOCKS : Categories.MISC, i % 3 == 0 ? "minecraft:diamond" : "minecraft:emerald", 1 + i * 997L % 100000));
        Map<String, Listing> by = new HashMap<>();
        for(Listing l : list)
            by.put(l.id, l);
        Function<String, Listing> lookup = by::get;
        out.put("list", MineBayPages.list(v, list, list, null, "", ShopParams.Sort.DEFAULT, 1, MineBayPages.Notice.ok("买好了：" + LONG_ZH + " ×64")));
        out.put("search-long", MineBayPages.list(v, list, Collections.<Listing>emptyList(), Categories.MISC, LONG_EN + LONG_EN, ShopParams.Sort.PRICE_DESC, 1, null));
        out.put("item", MineBayPages.item(v, list.get(0), 2, MineBayPages.Notice.bad("数量不对：只能填 1–64 的整数，你填的是 " + LONG_EN), "999999999"));
        out.put("item-en", MineBayPages.item(v, list.get(1), 0, null, null));
        Cart cart = new Cart();
        for(int i = 0; i < 6; i++)
            cart.add(list.get(i).id, 64, 64);
        cart.add("config:gone", 1, 64);
        Checkout.Plan plan = Checkout.plan(cart.lines(), lookup);
        out.put("cart", MineBayPages.cart(v, cart, lookup, plan, MineBayPages.Notice.bad("有一件商品下架了"), Collections.singletonMap(list.get(0).id, "abc")));
        out.put("checkout", MineBayPages.checkout(v, plan, lookup, "nonce1", "locnonce-123456", null, null));
        out.put("remove", MineBayPages.removeConfirm(v, list.get(0).id, list.get(0), 3));
        List<Orders.Order> orders = new ArrayList<>();
        for(int o = 0; o < 3; o++)
        {
            List<Orders.Item> items = new ArrayList<>();
            for(int i = 0; i < 4; i++)
                items.add(new Orders.Item(list.get(i).id, list.get(i).icon, 64, i % 2 == 0 ? null : LONG_EN, "染料", 64, "minecraft:emerald", 1_000_000L));
            orders.add(new Orders.Order(1000 - o, 1_700_000_000_000L, items, "ord_abcdefghijkmnpqr" + o));
        }
        out.put("orders", MineBayPages.orders(v, orders, 1, MineBayPages.Notice.ok("下单成功"), 1000));
        out.put("order-detail", MineBayPages.orderDetail(v, orders.get(0)));
        out.put("help", MineBayPages.help(v));
        out.put("account", MineBayPages.account(v, true, MineBayPages.Notice.retry("身份验证没有通过。设置没有改。", "g_x", "重新验证", "abc")));
        out.put("wait-verify", MineBayPages.waitVerify(v, "g_abc"));
        out.put("login-needed", MineBayPages.loginNeeded(MineBayPages.Viewer.anonymous("t"), "/cart"));
        out.put("unpaid", MineBayPages.unpaid(v, "https://pay.yr/c/ord_aaaaaaaaaaaaaaaa"));
        List<SupportChat.Message> msgs = new ArrayList<>();
        msgs.add(new SupportChat.Message(SupportChat.Role.PLAYER, "我买的 " + LONG_EN + " 怎么还没到？订单 https://minebay.yr/orders?highlight=1000&page=1", 1_763_200_000_000L));
        msgs.add(new SupportChat.Message(SupportChat.Role.AGENT, "您好！" + LONG_ZH + "已经发货了，请看看背包。\n第二行", 1_763_200_060_000L));
        out.put("support", MineBayPages.support(v, true, msgs, false, MineBayPages.Notice.bad("发送失败：太快了"), "我想问一下 " + LONG_EN, 20, false));
        out.put("problem", MineBayPages.problem(new MineBayPages.Viewer(null, 0, "t"), "出错了：" + LONG_EN, "说明：https://averyveryverylongdomainnamewithoutanybreaks.yr/" + LONG_EN));
        return out;
    }
}
