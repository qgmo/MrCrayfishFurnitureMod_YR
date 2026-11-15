package com.mrcrayfish.furniture.web.minebay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutTest
{
    static final class Offer implements Checkout.Offer
    {
        final String id, currency;
        final long price;
        final int max;

        Offer(String id, String currency, long price, int max)
        {
            this.id = id;
            this.currency = currency;
            this.price = price;
            this.max = max;
        }

        public String id() { return id; }
        public String currency() { return currency; }
        public long unitPrice() { return price; }
        public int maxPerOrder() { return max; }
    }

    /** 假的准备：某件准备不了（返回 null 或抛异常） */
    static final class FakePreparer implements Checkout.Preparer<String>
    {
        String failPrepare;
        boolean throwPrepare;
        final List<String> asked = new ArrayList<>();

        public List<String> prepare(Checkout.Item item) throws Exception
        {
            asked.add(item.offer.id());
            if(item.offer.id().equals(failPrepare))
            {
                if(throwPrepare)
                    throw new IllegalStateException("回调出错");
                return null;
            }
            return Collections.singletonList(item.offer.id() + "x" + item.quantity);
        }
    }

    static Function<String, Checkout.Offer> shop(Offer... offers)
    {
        Map<String, Checkout.Offer> m = new HashMap<>();
        for(Offer o : offers)
            m.put(o.id, o);
        return m::get;
    }

    static Cart cart(Object... idQty)
    {
        Cart c = new Cart();
        for(int i = 0; i < idQty.length; i += 2)
            assertEquals(Cart.Change.OK, c.add((String) idQty[i], (Integer) idQty[i + 1], 64));
        return c;
    }

    final Function<String, Checkout.Offer> shop = shop(new Offer("config:clay", "emerald", 1, 64), new Offer("config:armor", "diamond", 8, 64),
            new Offer("config:book", "emerald", 8, 64), new Offer("mod:floppy", "diamond", 3, 2));

    @Test
    void totalsArePerCurrency()
    {
        Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2, "config:book", 4, "config:gone", 1).lines(), shop);
        assertEquals(3, p.items.size());
        assertEquals(Long.valueOf(3 + 32), p.totals.get("emerald"));
        assertEquals(Long.valueOf(16), p.totals.get("diamond"));
        assertEquals(Arrays.asList("emerald", "diamond"), new ArrayList<>(p.totals.keySet()), "按出现顺序");
        assertEquals(Checkout.Unavailable.GONE, p.unavailable.get("config:gone"));
    }

    @Test
    void bigTotalsDoNotOverflow()
    {
        Function<String, Checkout.Offer> pricey = shop(new Offer("a:a", "emerald", MineBayProduct.MAX_PRICE, 64));
        Cart c = new Cart();
        c.add("a:a", 64, 64);
        assertEquals(Long.valueOf(64L * MineBayProduct.MAX_PRICE), Checkout.plan(c.lines(), pricey).totals.get("emerald"));
    }

    @Test
    void overLimitLinesStayInCart()
    {
        Checkout.Plan p = Checkout.plan(cart("mod:floppy", 5, "config:clay", 1).lines(), shop);
        assertEquals(Checkout.Unavailable.LIMIT, p.unavailable.get("mod:floppy"));
        assertEquals(1, p.items.size());
        assertNull(Checkout.single(shop.apply("mod:floppy"), 3));
        assertNull(Checkout.single(null, 1));
        assertNull(Checkout.single(shop.apply("config:clay"), 0));
        assertNotNull(Checkout.single(shop.apply("mod:floppy"), 2));
    }

    @Test
    void signatureChangesWithCartOrPrice()
    {
        String a = Checkout.plan(cart("config:clay", 3).lines(), shop).signature;
        assertEquals(a, Checkout.plan(cart("config:clay", 3).lines(), shop).signature);
        assertNotEquals(a, Checkout.plan(cart("config:clay", 4).lines(), shop).signature);
        assertNotEquals(a, Checkout.plan(cart("config:clay", 3).lines(), shop(new Offer("config:clay", "emerald", 2, 64))).signature, "改价");
        assertNotEquals(a, Checkout.plan(cart("config:clay", 3).lines(), shop(new Offer("config:clay", "gold", 1, 64))).signature, "换货币");
    }

    /** 库存不够的（改接 pay.yr 以前是“钱不够”时一样不扣）：留在购物车里不买；立即购买买不了 */
    @Test
    void stockShortLinesStayInCart()
    {
        Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2).lines(), shop, id -> id.equals("config:clay") ? 2 : -1);
        assertEquals(Checkout.Unavailable.STOCK, p.unavailable.get("config:clay"));
        assertEquals(Collections.singletonMap("config:armor", 2), p.quantities());
        assertTrue(Checkout.plan(cart("config:clay", 3).lines(), shop, id -> 3).unavailable.isEmpty(), "正好够");
        assertEquals(Checkout.Unavailable.STOCK, Checkout.plan(cart("config:clay", 1).lines(), shop, id -> 0).unavailable.get("config:clay"), "卖完了");
        assertNull(Checkout.single(shop.apply("config:clay"), 3, 2));
        assertNotNull(Checkout.single(shop.apply("config:clay"), 3, 3));
        assertNotNull(Checkout.single(shop.apply("config:clay"), 3, -1), "-1 为不限");
    }

    /** 准备不了一样（返回 null 或抛异常），整单不开 */
    @Test
    void failedPreparationOpensNothing()
    {
        for(boolean thr : new boolean[]{false, true})
        {
            FakePreparer t = new FakePreparer();
            t.failPrepare = "config:armor";
            t.throwPrepare = thr;
            Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2).lines(), shop);
            assertNull(Checkout.prepare(p, t));
        }
        assertNull(Checkout.prepare(Checkout.plan(cart("config:clay", 1).lines(), shop), item -> Collections.<String>emptyList()), "空的也算准备不了");
    }

    /** 准备好的和商品一一对应（改接 pay.yr 以前这里测“扣不齐时退回”；扣钱现在由 pay.yr 做） */
    @Test
    void preparedGoodsMatchItems()
    {
        FakePreparer t = new FakePreparer();
        Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2, "config:gone", 1).lines(), shop);
        List<List<String>> g = Checkout.prepare(p, t);
        assertEquals(Arrays.asList(Collections.singletonList("config:clayx3"), Collections.singletonList("config:armorx2")), g);
        assertEquals(Arrays.asList("config:clay", "config:armor"), t.asked, "下架的不准备");
    }

    /** 付款以后发货：先全部发出，再逐行通知；买走的从购物车里拿掉，下架的留着 */
    @Test
    void deliverGivesEverythingThenNotifies()
    {
        Cart c = cart("config:clay", 3, "config:armor", 2, "config:book", 4, "config:gone", 1);
        Checkout.Plan p = Checkout.plan(c.lines(), shop);
        List<List<String>> goods = Checkout.prepare(p, new FakePreparer());
        List<String> log = new ArrayList<>();
        List<Integer> idx = Arrays.asList(0, 1, 2);
        Checkout.deliver(idx, goods::get, new Checkout.Shipper<Integer, String>()
        {
            public void give(Integer line, List<String> g) { log.add("give " + g.get(0)); }

            public void delivered(Integer line, List<String> g) { log.add("done " + g.get(0)); }
        });
        assertEquals(Arrays.asList("give config:clayx3", "give config:armorx2", "give config:bookx4", "done config:clayx3", "done config:armorx2", "done config:bookx4"), log);
        c.removeBought(p.quantities());
        assertEquals(1, c.lines().size(), "下架的留在购物车里");
        assertEquals("config:gone", c.lines().get(0).id);
    }

    @Test
    void emptyPlanPreparesNothing()
    {
        FakePreparer t = new FakePreparer();
        assertNull(Checkout.prepare(Checkout.plan(new Cart().lines(), shop), t));
        assertNull(Checkout.prepare(Checkout.plan(cart("config:gone", 1).lines(), shop), t));
        assertTrue(t.asked.isEmpty());
    }

    @Test
    void totalsStillPerCurrency()
    {
        Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2).lines(), shop);
        assertEquals(Long.valueOf(3), p.totals.get("emerald"));
        assertEquals(Long.valueOf(16), p.totals.get("diamond"));
    }
}
