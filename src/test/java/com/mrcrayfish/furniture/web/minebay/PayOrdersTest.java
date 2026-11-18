package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PayOrdersTest
{
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static PayOrders.Entry entry(String pay, UUID buyer, String product, int n)
    {
        NBTTagCompound g = new NBTTagCompound();
        g.setString("id", "minecraft:stone");
        g.setByte("Count", (byte) 1);
        return new PayOrders.Entry(pay, 7, buyer, "Alice", 1000, 2000, "http://pay.yr/c/" + pay,
                Collections.singletonList(new Orders.Item(product, "minecraft:stone", 1, null, "石头", n, "minecraft:emerald", 3L * n)),
                Collections.singletonList(new PayOrders.Line(product, n, Collections.singletonList(g))), true);
    }

    /** 还没付的单预留库存；付了以后不再算预留（库存那时正式扣）；同一张只记一次付款 */
    @Test
    void reservations()
    {
        PayOrders o = new PayOrders();
        o.add(entry("ord_aaaaaaaaaaaaaaaa", A, "youranscp:basic", 2));
        o.add(entry("ord_bbbbbbbbbbbbbbbb", B, "youranscp:basic", 3));
        assertEquals(5, o.reserved("youranscp:basic"));
        assertEquals(0, o.reserved("other:x"));
        assertEquals("ord_aaaaaaaaaaaaaaaa", o.openOf(A).payId);
        assertTrue(o.markPaid("ord_aaaaaaaaaaaaaaaa"));
        assertFalse(o.markPaid("ord_aaaaaaaaaaaaaaaa"), "同一张只记一次");
        assertFalse(o.markPaid("ord_cccccccccccccccc"));
        assertEquals(3, o.reserved("youranscp:basic"));
        assertNull(o.openOf(A));
        assertEquals(1, o.paidOf(A).size());
        assertNotNull(o.remove("ord_bbbbbbbbbbbbbbbb"));
        assertEquals(0, o.reserved("youranscp:basic"), "过期、取消后放回");
    }

    /**
     * pay.yr 补发同一笔付款的回调（服主 11-18：至少一次、可能重复）：第一次照付了处理；之后不管是付了还没发货、发完货拿掉了、
     * 还是重开（读存档）以后，都认得是处理过的，不再处理，也不当成不认识的单去退款
     */
    @Test
    void resentPaymentIsHandledOnce()
    {
        PayOrders o = new PayOrders();
        o.add(entry("ord_aaaaaaaaaaaaaaaa", A, "youranscp:basic", 2));
        assertEquals(PayOrders.Seen.OPEN, o.seen("ord_aaaaaaaaaaaaaaaa"));
        assertTrue(o.markPaid("ord_aaaaaaaaaaaaaaaa"));
        assertEquals(PayOrders.Seen.HANDLED, o.seen("ord_aaaaaaaaaaaaaaaa"), "付了还没发货");
        assertFalse(o.markPaid("ord_aaaaaaaaaaaaaaaa"));
        // 重开（付了还没发货时）
        PayOrders r = PayOrders.read(o.write());
        r.readHandled(o.writeHandled());
        assertEquals(PayOrders.Seen.HANDLED, r.seen("ord_aaaaaaaaaaaaaaaa"));
        // 发完货拿掉，再重开
        assertNotNull(r.remove("ord_aaaaaaaaaaaaaaaa"));
        assertEquals(PayOrders.Seen.HANDLED, r.seen("ord_aaaaaaaaaaaaaaaa"), "发完货以后还认得");
        PayOrders r2 = PayOrders.read(r.write());
        r2.readHandled(r.writeHandled());
        assertEquals(PayOrders.Seen.HANDLED, r2.seen("ord_aaaaaaaaaaaaaaaa"), "重开以后还认得");
        assertNull(r2.get("ord_aaaaaaaaaaaaaaaa"));
        // 没付过的、不认识的
        assertEquals(PayOrders.Seen.UNKNOWN, r2.seen("ord_cccccccccccccccc"));
        o.add(entry("ord_bbbbbbbbbbbbbbbb", B, "youranscp:basic", 1));
        assertNotNull(o.remove("ord_bbbbbbbbbbbbbbbb"));
        assertEquals(PayOrders.Seen.UNKNOWN, o.seen("ord_bbbbbbbbbbbbbbbb"), "过期、取消拿掉的没付过，不算处理过");
        // 只记最近的
        PayOrders big = new PayOrders();
        for(int i = 0; i <= PayOrders.MAX_HANDLED; i++)
        {
            String id = String.format("ord_%016d", i);
            big.add(entry(id, A, "a:b", 1));
            big.markPaid(id);
            big.remove(id);
        }
        assertEquals(PayOrders.Seen.UNKNOWN, big.seen(String.format("ord_%016d", 0)));
        assertEquals(PayOrders.Seen.HANDLED, big.seen(String.format("ord_%016d", PayOrders.MAX_HANDLED)));
    }

    /** 存档：状态、要发的物品、订单的样子都在 */
    @Test
    void saveAndLoad()
    {
        PayOrders o = new PayOrders();
        o.add(entry("ord_aaaaaaaaaaaaaaaa", A, "config:abc", 2));
        o.add(entry("ord_bbbbbbbbbbbbbbbb", B, "config:abc", 1));
        o.markPaid("ord_bbbbbbbbbbbbbbbb");
        PayOrders r = PayOrders.read(o.write());
        assertEquals(2, r.all().size());
        PayOrders.Entry a = r.get("ord_aaaaaaaaaaaaaaaa");
        assertEquals(PayOrders.State.OPEN, a.state());
        assertEquals(A, a.buyer);
        assertEquals(7, a.number);
        assertEquals(2000, a.expires);
        assertTrue(a.fromCart);
        assertEquals("minecraft:stone", a.lines.get(0).goods.get(0).getString("id"));
        assertEquals(Collections.singletonMap("config:abc", 2), a.quantities());
        assertEquals("石头", a.items.get(0).sortName);
        assertEquals(PayOrders.State.PAID, r.get("ord_bbbbbbbbbbbbbbbb").state());
        assertEquals(2, r.reserved("config:abc"));
    }

    /** 上限：满了不能再加 */
    @Test
    void capacity()
    {
        PayOrders o = new PayOrders();
        for(int i = 0; i < PayOrders.MAX; i++)
            o.add(entry(String.format("ord_%016d", i), A, "a:b", 1));
        assertTrue(o.isFull());
        assertThrows(IllegalStateException.class, () -> o.add(entry("ord_zzzzzzzzzzzzzzzz", B, "a:b", 1)));
    }

    /** 订单记录带上 pay.yr 单号，能按单号找回 */
    @Test
    void ordersRememberPayId()
    {
        Orders os = new Orders();
        os.add(new Orders.Order(2, 2, Arrays.asList(new Orders.Item("a:b", "minecraft:stone", 1, null, "石头", 1, "minecraft:emerald", 1)), "ord_aaaaaaaaaaaaaaaa"));
        Orders r = Orders.read(os.write());
        assertEquals(2, r.findByPay("ord_aaaaaaaaaaaaaaaa").number);
        assertNull(r.findByPay("ord_bbbbbbbbbbbbbbbb"));
    }
}
