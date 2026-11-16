package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MineBay 在线客服“小 Bay”（服主 11-15）：玩家发一条消息 → 主线程上记进对话、组好提示词 → 后台问大模型 → 回到主线程把回答记进对话。
 * <ul>
 * <li>只答不办：客服只有回答（与转给管理员的留言），没有任何能改 MineBay 数据的动作；数据只给 MineBay 的商品表与登录者自己的订单（{@link SupportPrompt}）。</li>
 * <li>限额：每个帐号每小时最多 {@link SupportConfig#messagesPerHour} 条；客服没能回复的那条不算。同一帐号同一时间只问一条（上一条还在等回答时不收新的）。</li>
 * <li>留言：回答里带留言时记进 {@link SupportData}，在线的管理员（OP）聊天栏里提示，用 {@code /minebay tickets} 查看。</li>
 * </ul>
 * 只在服务端主线程上调用（大模型的回答由后台线程交回主线程）。
 */
public final class Support
{
    static final long HOUR = 3600_000L;
    /** 等回答最多等多久（两个接口各一次超时再多留一点）；过了就当没回复 */
    static final long STALE_MS = 2 * 120_000L + 10_000L;
    public static final String CANNOT_REPLY = "（" + SupportPrompt.AGENT_NAME + " 暂时没能回复，这条不算次数，请稍后再试。）";

    /** 帐号 → 开始等回答的时间 */
    private static final Map<UUID, Long> PENDING = new HashMap<>();
    private static SupportLlm llm;

    private Support() {}

    /** 模组的日志（单元测试里模组没加载时用同名的） */
    static org.apache.logging.log4j.Logger log()
    {
        org.apache.logging.log4j.Logger l = com.mrcrayfish.furniture.MrCrayfishFurnitureMod.logger();
        return l != null ? l : org.apache.logging.log4j.LogManager.getLogger("cfm");
    }

    /** 服务器启动时（读完配置）：用配置里的接口 */
    public static void start()
    {
        llm = SupportLlm.fromConfig();
        PENDING.clear();
        if(llm.configured())
            Support.log().info("MineBay support: on ({}), {} messages per hour per account", llm.describe(), SupportConfig.messagesPerHour);
        else
            Support.log().info("MineBay support: off (no apiKey in config/{})", SupportConfig.FILE);
    }

    /** 测试用：换成别的大模型 */
    static void use(SupportLlm l)
    {
        llm = l;
        PENDING.clear();
    }

    public static boolean available()
    {
        return llm != null && llm.configured();
    }

    /** 这个帐号是不是正在等回答 */
    public static boolean waiting(UUID player, long now)
    {
        Long since = PENDING.get(player);
        if(since == null)
            return false;
        if(now - since > STALE_MS)
        {
            PENDING.remove(player);
            return false;
        }
        return true;
    }

    /** 发消息的结果 */
    public enum Outcome
    {
        SENT, UNAVAILABLE, EMPTY_OR_BAD, TOO_LONG, BUSY, LIMIT, FULL
    }

    /** 还要等几分钟才能再问（{@link Outcome#LIMIT} 时） */
    public static int minutesToWait(SupportData data, UUID player, long now)
    {
        long ms = data.chat(player).waitFor(now, HOUR, SupportConfig.messagesPerHour);
        return (int) Math.max(1, (ms + 59_999) / 60_000);
    }

    /**
     * 玩家发一条消息（主线程）。收下了就记进对话、开始问，返回 {@link Outcome#SENT}；否则什么都不记，说明原因。
     * @param server 回答交回主线程用；null 时在回答的线程上直接记（测试）
     */
    public static Outcome send(@Nullable MinecraftServer server, MineBayData shop, SupportData data, UUID player, String name, @Nullable String raw, long now)
    {
        return send(server, () -> system(shop, player, name, now), data, player, name, raw, now);
    }

    /** 同上；系统提示由 {@code system} 给（测试用固定的） */
    static Outcome send(@Nullable MinecraftServer server, java.util.function.Supplier<String> systemPrompt, SupportData data, UUID player, String name,
                        @Nullable String raw, long now)
    {
        if(!available())
            return Outcome.UNAVAILABLE;
        String text = SupportText.message(raw);
        if(text == null)
            return SupportText.tooLong(raw) ? Outcome.TOO_LONG : Outcome.EMPTY_OR_BAD;
        if(waiting(player, now))
            return Outcome.BUSY;
        if(data.chat(player).sentWithin(now, HOUR) >= SupportConfig.messagesPerHour)
            return Outcome.LIMIT;
        List<SupportChat.Message> history = new ArrayList<>(data.chat(player).messages());
        SupportChat chat = data.chatForChange(player);
        if(chat == null)
            return Outcome.FULL;
        String system = systemPrompt.get();
        chat.add(new SupportChat.Message(SupportChat.Role.PLAYER, text, now));
        chat.countSent(now);
        PENDING.put(player, now);
        List<SupportLlm.Message> messages = SupportPrompt.messages(system, history, text);
        Support.log().info("MineBay support: {} asked ({} chars)", name, text.length());
        llm.ask(messages, SupportPrompt.MAX_TOKENS, SupportPrompt.TEMPERATURE, r ->
        {
            Runnable apply = () -> answered(server, data, player, name, now, r);
            if(server != null)
                server.addScheduledTask(apply);
            else
                apply.run();
        });
        return Outcome.SENT;
    }

    /** 回答回来了（主线程） */
    static void answered(@Nullable MinecraftServer server, SupportData data, UUID player, String name, long askedAt, SupportLlm.Result r)
    {
        Long since = PENDING.get(player);
        if(since != null && since == askedAt)
            PENDING.remove(player);
        SupportChat chat = data.chatForChange(player);
        if(chat == null)
            return;
        long now = System.currentTimeMillis();
        SupportText.Reply reply = r.ok() ? SupportText.reply(r.content) : null;
        if(reply == null || reply.text.isEmpty())
        {
            if(reply == null)
                Support.log().warn("MineBay support: no answer for {} ({})", name, r.error);
            chat.uncountLast();
            chat.add(new SupportChat.Message(SupportChat.Role.NOTE, CANNOT_REPLY, now));
            return;
        }
        String text = reply.text;
        if(reply.ticket != null)
        {
            SupportData.Ticket t = data.addTicket(player, name, reply.ticket, now);
            if(t != null)
            {
                Support.log().info("MineBay support: ticket #{} from {}", t.id, name);
                notifyOps(server, t);
                chat.add(new SupportChat.Message(SupportChat.Role.AGENT, text, now));
                chat.add(new SupportChat.Message(SupportChat.Role.NOTE, "（已转给管理员，留言编号 #" + t.id + "。）", now));
                return;
            }
            chat.add(new SupportChat.Message(SupportChat.Role.AGENT, text, now));
            chat.add(new SupportChat.Message(SupportChat.Role.NOTE, "（留言没有转出：留言箱满了，请直接联系服主。）", now));
            return;
        }
        chat.add(new SupportChat.Message(SupportChat.Role.AGENT, text, now));
        Support.log().debug("MineBay support: answered {} via {}", name, r.endpoint);
    }

    /** 新留言：在线的管理员聊天栏里提示一句 */
    static void notifyOps(@Nullable MinecraftServer server, SupportData.Ticket t)
    {
        if(server == null)
            return;
        for(EntityPlayerMP p : server.getPlayerList().getPlayers())
        {
            if(server.getPlayerList().canSendCommands(p.getGameProfile()))
                p.sendMessage(opLine("MineBay 客服留言 #" + t.id + "（" + t.playerName + "）：" + SupportText.clip(t.text, 60) + "  /minebay ticket " + t.id + " 查看"));
        }
    }

    static TextComponentString opLine(String s)
    {
        TextComponentString c = new TextComponentString(s);
        c.getStyle().setColor(TextFormatting.GOLD);
        return c;
    }

    /** 管理员（OP）上线时：有没处理的留言就提示一句 */
    public static final class Events
    {
        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void onLogin(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event)
        {
            if(!(event.player instanceof EntityPlayerMP))
                return;
            MinecraftServer server = event.player.getServer();
            if(server == null || !server.getPlayerList().canSendCommands(event.player.getGameProfile()))
                return;
            SupportData d = SupportData.get(server);
            int n = d == null ? 0 : d.openTickets();
            if(n > 0)
                event.player.sendMessage(opLine("MineBay 有 " + n + " 条客服留言没处理（/minebay tickets 查看）"));
        }
    }

    // ---------------------------------------------------------------- 数据

    /** 系统提示（主线程：读商品表、这个帐号的订单） */
    static String system(MineBayData shop, UUID player, String name, long now)
    {
        Catalog catalog = Catalog.get();
        List<SupportPrompt.Product> products = new ArrayList<>();
        for(Listing l : catalog.listings)
        {
            if(products.size() >= SupportPrompt.MAX_PRODUCTS)
                break;
            products.add(product(catalog, shop, l, player, now));
        }
        List<Orders.Order> orders = shop.orders(player).list();
        List<SupportPrompt.OrderRow> rows = new ArrayList<>();
        PayOrders.Entry open = shop.payOrders().openOf(player);
        int total = orders.size();
        if(open != null)
        {
            rows.add(row(open.number, open.created, open.items, "待付款（收款单还没付，" + MineBayPages.TIME.format(java.time.Instant.ofEpochMilli(open.expires)) + " 前有效）"));
            total++;
        }
        Map<Long, Boolean> undelivered = new HashMap<>();
        for(PayOrders.Entry e : shop.payOrders().paidOf(player))
            undelivered.put(e.number, true);
        for(Orders.Order o : orders)
        {
            if(rows.size() >= SupportPrompt.MAX_ORDERS)
                break;
            rows.add(row(o.number, o.time, o.items, undelivered.containsKey(o.number) ? "已付款，还没发货（等 " + name + " 上线就发）" : "已付款，已发货"));
        }
        return SupportPrompt.system(name, MineBayPages.TIME.format(java.time.Instant.ofEpochMilli(now)), products, catalog.listings.size(), rows, total,
                cart(catalog, shop, player, now));
    }

    /** 登录者的购物车：每样能不能结算、为什么不能（同购物车页的判断） */
    static List<SupportPrompt.CartRow> cart(Catalog catalog, MineBayData shop, UUID player, long now)
    {
        List<Cart.Line> lines = shop.cart(player).lines();
        Checkout.Plan plan = Checkout.plan(lines, catalog::listing, id ->
        {
            Listing l = catalog.listing(id);
            return l == null ? -1 : shop.remaining(l.id, l.stock, now, player);
        });
        List<SupportPrompt.CartRow> out = new ArrayList<>();
        for(Cart.Line line : lines)
        {
            Listing l = catalog.listing(line.id);
            Checkout.Unavailable why = plan.unavailable.get(line.id);
            String status;
            if(why == Checkout.Unavailable.GONE)
                status = "不能结算：已下架";
            else if(why == Checkout.Unavailable.LIMIT)
                status = "不能结算：这件一单最多买 " + Cart.limit(l.maxPerOrder) + " 份，要改少";
            else if(why == Checkout.Unavailable.STOCK)
            {
                long left = shop.remaining(l.id, l.stock, now, player);
                status = "不能结算：" + (left <= 0 ? "已卖完" : "库存只剩 " + left + " 份");
            }
            else
                status = "能结算";
            out.add(new SupportPrompt.CartRow(line.id, l == null ? line.id : l.sortName, line.quantity(), status));
        }
        return out;
    }

    static SupportPrompt.Product product(Catalog catalog, MineBayData shop, Listing l, UUID player, long now)
    {
        String stock;
        if(l.stock == null)
            stock = "不限量";
        else
        {
            long left = shop.remaining(l.id, l.stock, now, player);
            Stock.State st = shop.stock(l.id, l.stock, now);
            String wait = st == null ? "" : Stock.describeWait(st, now);
            stock = left > 0 ? "剩 " + left + " 份（" + Stock.describe(l.stock) + "）" : "卖完了" + (wait.isEmpty() ? "" : "，" + wait);
        }
        Categories.Category c = Categories.get(l.category);
        StringBuilder desc = new StringBuilder();
        for(List<Listing.Part> line : l.description)
        {
            if(desc.length() > 0)
                desc.append("；");
            for(Listing.Part p : line)
                desc.append(p.text);
        }
        return new SupportPrompt.Product(l.id, l.sortName, c == null ? l.category : c.name, l.count, l.price, catalog.currencyName(l.id), stock, l.maxPerOrder,
                SupportText.clip(desc.toString(), 200));
    }

    private static SupportPrompt.OrderRow row(long number, long time, List<Orders.Item> items, String status)
    {
        StringBuilder what = new StringBuilder();
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for(Orders.Item i : items)
        {
            if(what.length() > 0)
                what.append("、");
            what.append(i.sortName).append(" ×").append(i.quantity).append(" 份");
            totals.merge(i.currency, i.cost, Long::sum);
        }
        StringBuilder cost = new StringBuilder();
        for(Map.Entry<String, Long> e : totals.entrySet())
        {
            if(cost.length() > 0)
                cost.append(" + ");
            cost.append(e.getValue()).append(' ').append(Catalog.currencyNameOf(e.getKey()));
        }
        return new SupportPrompt.OrderRow(MineBayPages.number(number), MineBayPages.TIME.format(java.time.Instant.ofEpochMilli(time)),
                SupportText.clip(what.toString(), 300), cost.toString(), status);
    }

    /** 测试、命令用：等回答的帐号数 */
    static int pendingCount()
    {
        return PENDING.size();
    }
}
