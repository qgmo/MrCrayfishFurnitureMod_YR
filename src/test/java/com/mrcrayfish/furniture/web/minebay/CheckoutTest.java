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

    /** 假的背包：货币 → 数量；发出去的东西记下来 */
    static final class FakeTill implements Checkout.Till<String>
    {
        final Map<String, Long> wallet = new HashMap<>();
        final List<String> given = new ArrayList<>();
        final List<String> delivered = new ArrayList<>();
        String failPrepare;
        boolean throwPrepare;
        String shortTake;

        public long have(String c) { return wallet.getOrDefault(c, 0L); }

        public List<String> prepare(Checkout.Item item) throws Exception
        {
            if(item.offer.id().equals(failPrepare))
            {
                if(throwPrepare)
                    throw new IllegalStateException("回调出错");
                return null;
            }
            return Collections.singletonList(item.offer.id() + "x" + item.quantity);
        }

        public long take(String c, long amount)
        {
            long got = c.equals(shortTake) ? amount - 1 : amount;
            wallet.merge(c, -got, Long::sum);
            return got;
        }

        public void refund(String c, long amount) { wallet.merge(c, amount, Long::sum); }

        public void give(Checkout.Item item, List<String> goods) { given.addAll(goods); }

        public void delivered(Checkout.Item item, List<String> goods) { delivered.addAll(goods); }
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
        assertTrue(p.affordable(c -> c.equals("emerald") ? 35L : 16L));
        assertFalse(p.affordable(c -> c.equals("emerald") ? 34L : 16L));
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

    @Test
    void poorTakesNothing()
    {
        FakeTill t = new FakeTill();
        t.wallet.put("emerald", 100L);
        t.wallet.put("diamond", 15L);
        Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2).lines(), shop);
        assertEquals(Checkout.Outcome.POOR, Checkout.run(p, t));
        assertEquals(Long.valueOf(100), t.wallet.get("emerald"), "一种货币不够，另一种也不扣");
        assertEquals(Long.valueOf(15), t.wallet.get("diamond"));
        assertTrue(t.given.isEmpty());
    }

    @Test
    void failedPreparationTakesNothing()
    {
        for(boolean thr : new boolean[]{false, true})
        {
            FakeTill t = new FakeTill();
            t.wallet.put("emerald", 100L);
            t.wallet.put("diamond", 100L);
            t.failPrepare = "config:armor";
            t.throwPrepare = thr;
            Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2).lines(), shop);
            assertEquals(Checkout.Outcome.FAILED, Checkout.run(p, t));
            assertEquals(Long.valueOf(100), t.wallet.get("emerald"));
            assertEquals(Long.valueOf(100), t.wallet.get("diamond"));
            assertTrue(t.given.isEmpty(), "一样准备不了，别的也不发");
        }
    }

    @Test
    void shortTakeIsRefunded()
    {
        FakeTill t = new FakeTill();
        t.wallet.put("emerald", 100L);
        t.wallet.put("diamond", 100L);
        t.shortTake = "diamond";
        Checkout.Plan p = Checkout.plan(cart("config:clay", 3, "config:armor", 2).lines(), shop);
        assertEquals(Checkout.Outcome.FAILED, Checkout.run(p, t));
        assertEquals(Long.valueOf(100), t.wallet.get("emerald"), "先扣了的退回");
        assertEquals(Long.valueOf(100), t.wallet.get("diamond"));
        assertTrue(t.given.isEmpty());
    }

    @Test
    void successTakesExactlyAndDeliversEverything()
    {
        FakeTill t = new FakeTill();
        t.wallet.put("emerald", 35L);
        t.wallet.put("diamond", 16L);
        Cart c = cart("config:clay", 3, "config:armor", 2, "config:book", 4, "config:gone", 1);
        Checkout.Plan p = Checkout.plan(c.lines(), shop);
        assertEquals(Checkout.Outcome.OK, Checkout.run(p, t));
        assertEquals(Long.valueOf(0), t.wallet.get("emerald"));
        assertEquals(Long.valueOf(0), t.wallet.get("diamond"));
        assertEquals(Arrays.asList("config:clayx3", "config:armorx2", "config:bookx4"), t.given);
        assertEquals(t.given, t.delivered);
        c.removeBought(p.quantities());
        assertEquals(1, c.lines().size(), "下架的留在购物车里");
        assertEquals("config:gone", c.lines().get(0).id);
    }

    @Test
    void emptyPlanDoesNothing()
    {
        FakeTill t = new FakeTill();
        assertEquals(Checkout.Outcome.EMPTY, Checkout.run(Checkout.plan(new Cart().lines(), shop), t));
        assertEquals(Checkout.Outcome.EMPTY, Checkout.run(Checkout.plan(cart("config:gone", 1).lines(), shop), t));
    }
}
