package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.api.Recipes;
import com.mrcrayfish.furniture.computer.ComputerNetwork;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import com.mrcrayfish.furniture.web.WebContext;
import com.mrcrayfish.furniture.web.WebHandler;
import com.mrcrayfish.furniture.web.WebRequest;
import com.mrcrayfish.furniture.web.WebResponse;
import com.mrcrayfish.furniture.web.Websites;
import moe.qingu.youran.api.network.Ipv6Address;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.items.ItemHandlerHelper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MineBay 网站（{@code minebay.yr}，主机登记名 {@value #HOST_KEY}）：原来电脑界面里的 MineBay 商店改成了游戏内网站。
 * <ul>
 * <li>商品照旧取 {@link Recipes#getMineBayItems()}（含 CraftTweaker 改的）。</li>
 * <li>买：表单 POST /buy，在服务端主线程上做（{@link WebContext#onMainThread}）。按请求的来源地址找回发请求的电脑，
 * 再找它的操作者：电脑要在、有电，操作者要在线、开着这台电脑的界面（距离不超过 8 格）；商品编号要在范围里；
 * 付款从操作者的背包里扣（物品与数据值要对），够了才扣，买到的东西放进背包（满了掉在脚边）。不合格的记一条限频日志。</li>
 * <li>防别的网站骗点：购买表单带一个按来源地址发的随机令牌，POST 时核对。</li>
 * </ul>
 * 原来 MineBay 的安全修复（主线程、界面与方块实体核对、编号范围、价格与货币核对、没电拒绝）都保留在这里。
 */
public final class MineBaySite implements WebHandler
{
    public static final String HOST_KEY = "cfm:minebay";
    public static final String DOMAIN = "minebay.yr";
    static final int MAX_TOKENS = 1024;

    private final SecureRandom random = new SecureRandom();
    /** 来源地址 → 令牌（只在网络线程上用） */
    private final Map<Ipv6Address, String> tokens = new LinkedHashMap<Ipv6Address, String>(64, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Ipv6Address, String> eldest)
        {
            return size() > MAX_TOKENS;
        }
    };

    public static void register()
    {
        Websites.register(HOST_KEY, DOMAIN, new MineBaySite());
    }

    @Override
    public CompletableFuture<WebResponse> handle(WebRequest request, WebContext context)
    {
        if(request.path.equals("/") || request.path.equals("/index.xhtml"))
        {
            if(!request.method.equals("GET"))
                return CompletableFuture.completedFuture(WebResponse.error(405, "Method Not Allowed"));
            return context.onMainThread(MineBaySite::entries).thenApply(list -> WebResponse.xhtml(MineBayPages.shop(list,
                    parseInt(request.parameter("p"), 0), tokenFor(request.remoteAddress), message(request.parameter("r")), "ok".equals(request.parameter("r")))));
        }
        if(request.path.equals("/buy"))
        {
            if(!request.method.equals("POST"))
                return CompletableFuture.completedFuture(WebResponse.error(405, "Method Not Allowed"));
            int page = parseInt(request.parameter("p"), 0);
            String token = request.parameter("t");
            String expected = tokens.get(request.remoteAddress);
            if(token == null || expected == null || !expected.equals(token))
                return CompletableFuture.completedFuture(WebResponse.seeOther("/?p=" + page + "&r=token"));
            int index = parseInt(request.parameter("i"), -1);
            String n = request.parameter("n");
            int quantity = n == null || n.trim().isEmpty() ? 1 : parseInt(n.trim(), -1);
            if(quantity < 1 || quantity > MineBayPages.MAX_QUANTITY)
                return CompletableFuture.completedFuture(WebResponse.seeOther("/?p=" + page + "&r=qty"));
            Ipv6Address from = request.remoteAddress;
            return context.onMainThread(() -> buy(from, index, quantity)).thenApply(r -> WebResponse.seeOther("/?p=" + page + "&r=" + r));
        }
        return CompletableFuture.completedFuture(WebResponse.error(404, "Not Found"));
    }

    private String tokenFor(Ipv6Address address)
    {
        String t = tokens.get(address);
        if(t == null)
        {
            byte[] b = new byte[16];
            random.nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for(byte x : b)
                sb.append(String.format("%02x", x & 0xFF));
            t = sb.toString();
            tokens.put(address, t);
        }
        return t;
    }

    static int parseInt(String s, int dflt)
    {
        if(s == null || !s.matches("-?\\d{1,9}"))
            return dflt;
        return Integer.parseInt(s);
    }

    static String message(String code)
    {
        if(code == null)
            return null;
        switch(code)
        {
            case "ok": return "买好了，东西已经放进你的背包。";
            case "poor": return "背包里的钱不够。";
            case "nobody": return "这台电脑现在没有人在操作（要开着电脑的界面才能买）。";
            case "power": return "电脑没电了。";
            case "item": return "没有这件商品。";
            case "token": return "这个购买请求不是从 MineBay 的页面发出的，没有处理。请在本页重新点“购买”。";
            case "computer": return "只有家具电脑能在 MineBay 买东西。";
            case "qty": return "数量要填 1 到 " + MineBayPages.MAX_QUANTITY + " 之间的整数。";
            default: return null;
        }
    }

    /** 主线程：商品清单 */
    static List<MineBayPages.Entry> entries()
    {
        List<MineBayPages.Entry> out = new ArrayList<>();
        RecipeData[] data = Recipes.getMineBayItems();
        for(int i = 0; i < data.length; i++)
        {
            ItemStack in = data[i].getInput();
            ItemStack cur = data[i].getCurrency();
            if(in.isEmpty() || cur.isEmpty())
                continue;
            out.add(new MineBayPages.Entry(i, itemId(in), in.getCount(), itemId(cur), data[i].getPrice()));
        }
        return out;
    }

    static String itemId(ItemStack stack)
    {
        String id = String.valueOf(stack.getItem().getRegistryName());
        return stack.getHasSubtypes() || stack.getMetadata() != 0 ? id + "@" + stack.getMetadata() : id;
    }

    /** 主线程：买 quantity 份，返回结果代码（钱不够时一份也不买） */
    static String buy(Ipv6Address from, int index, int quantity)
    {
        TileEntityComputer computer = ComputerNetwork.computerAt(from);
        if(computer == null)
            return "computer";
        EntityPlayerMP player = computer.getOperatorPlayer();
        if(player == null)
            return "nobody";
        if(!computer.getAppliance().isRunning())
        {
            RejectLog.log(player, "MineBay purchase while the computer has no power at " + computer.getPos());
            return "power";
        }
        if(player.getDistanceSq(computer.getPos().getX() + 0.5, computer.getPos().getY() + 0.5, computer.getPos().getZ() + 0.5) > 64.0)
        {
            RejectLog.log(player, "MineBay purchase too far from computer at " + computer.getPos());
            return "nobody";
        }
        RecipeData[] data = Recipes.getMineBayItems();
        if(index < 0 || index >= data.length)
        {
            RejectLog.log(player, "MineBay purchase of unknown item " + index);
            return "item";
        }
        RecipeData recipe = data[index];
        ItemStack currency = recipe.getCurrency();
        if(quantity < 1 || quantity > MineBayPages.MAX_QUANTITY)
            return "qty";
        int unit = recipe.getPrice();
        if(currency.isEmpty() || unit <= 0 || recipe.getInput().isEmpty())
            return "item";
        int price = unit * quantity;
        if(price <= 0)
            return "item";
        NonNullList<ItemStack> inv = player.inventory.mainInventory;
        int have = 0;
        for(ItemStack s : inv)
        {
            if(matches(s, currency))
                have += s.getCount();
        }
        if(have < price)
            return "poor";
        int left = price;
        for(int i = 0; i < inv.size() && left > 0; i++)
        {
            ItemStack s = inv.get(i);
            if(matches(s, currency))
            {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
        player.inventory.markDirty();
        for(int i = 0; i < quantity; i++)
            ItemHandlerHelper.giveItemToPlayer(player, recipe.getInput().copy());
        player.inventoryContainer.detectAndSendChanges();
        Triggers.trigger(Triggers.MINEBAY_PURCHASE, player);
        MrCrayfishFurnitureMod.logger().info("MineBay: {} bought {} x {} for {} x {}", player.getName(), quantity, itemId(recipe.getInput()), price, itemId(currency));
        return "ok";
    }

    static boolean matches(ItemStack stack, ItemStack currency)
    {
        if(stack.isEmpty() || stack.getItem() != currency.getItem())
            return false;
        return !currency.getHasSubtypes() || stack.getMetadata() == currency.getMetadata();
    }
}
