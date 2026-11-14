package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.computer.PageParser;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.network.message.MessageComputerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 客户端上按电脑存的东西（只在内存，游戏不关就一直在）：
 * <ul>
 * <li>操作者的浏览器状态（{@link Browser}：地址栏、历史、表单里填的东西），关界面、断电、断线后再打开照旧；</li>
 * <li>旁观时服务端发来的页面（压缩的 {@link PageSnapshot}）与滚动位置。</li>
 * </ul>
 * 还有浏览器的渲染线程（一条，后台），渲染出错（含内存不够）都兜住，不会带崩游戏。
 */
public final class ComputerViews
{
    static final int MAX_BROWSERS = 16;

    static final BuiltinPages.Tr TR = (key, args) -> I18n.format(key, args);

    private static final Map<String, Browser> BROWSERS = new LinkedHashMap<String, Browser>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Browser> eldest)
        {
            return size() > MAX_BROWSERS;
        }
    };

    private static final Map<BlockPos, View> VIEWS = new HashMap<>();

    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "cfm 浏览器渲染");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private ComputerViews() {}

    /** 旁观时看到的页面 */
    public static final class View
    {
        @Nullable
        public byte[] page;
        public int scroll;
        /** 每收到一页加一 */
        public int version;
        /** 操作者正在打的字（框的编号 → 内容；地址栏是 {@link TypingSync#ADDRESS}）；换页面清空 */
        public final Map<Integer, TypingSync.Typed> typed = new HashMap<>();
    }

    /** 这台电脑的浏览器（按服务器 + 维度 + 位置，没有就建一个） */
    public static Browser browser(BlockPos pos)
    {
        return BROWSERS.computeIfAbsent(key(pos), k -> new Browser(pos.toImmutable()));
    }

    private static String key(BlockPos pos)
    {
        Minecraft mc = Minecraft.getMinecraft();
        // 按连着的服务器（对方地址）分：断线重连后还是同一份；单人游戏只有一份
        NetHandlerPlayClient connection = mc.getConnection();
        String where = mc.isSingleplayer() ? "local" : connection != null ? String.valueOf(connection.getNetworkManager().getRemoteAddress()) : "?";
        int dim = mc.world == null ? 0 : mc.world.provider.getDimension();
        return where + "|" + dim + "|" + pos.toLong();
    }

    public static View view(BlockPos pos)
    {
        return VIEWS.computeIfAbsent(pos.toImmutable(), k -> new View());
    }

    /** 服务端发来的（主线程） */
    public static void receive(BlockPos pos, int kind, int scroll, byte[] data)
    {
        View v = view(pos);
        if(kind == MessageComputerView.PAGE)
        {
            v.page = data;
            v.scroll = scroll;
            v.version++;
            v.typed.clear();
        }
        else if(kind == MessageComputerView.CLEAR)
        {
            v.page = null;
            v.scroll = 0;
            v.version++;
            v.typed.clear();
        }
        else if(kind == MessageComputerView.TYPING)
        {
            TypingSync.Typed t = MessageComputerView.decodeTyping(scroll, data);
            if(t != null && (v.typed.containsKey(t.field) || v.typed.size() <= PageDocument.MAX_CONTROLS))
                v.typed.put(t.field, t);
        }
        else if(kind == MessageComputerView.SCROLL)
        {
            v.scroll = scroll;
        }
    }

    /** 物品写法（注册名[@数据值]）→ 本地化名字 */
    @Nullable
    public static String itemName(String id)
    {
        ItemStack s = itemStack(id);
        return s.isEmpty() ? null : s.getDisplayName();
    }

    public static ItemStack itemStack(String id)
    {
        try
        {
            String name = id;
            int meta = 0;
            int at = id.indexOf('@');
            if(at >= 0)
            {
                name = id.substring(0, at);
                meta = Integer.parseInt(id.substring(at + 1));
            }
            Item item = Item.REGISTRY.getObject(new ResourceLocation(name));
            if(item == null || meta < 0 || meta > Short.MAX_VALUE)
                return ItemStack.EMPTY;
            return new ItemStack(item, 1, meta);
        }
        catch(RuntimeException e)
        {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 在渲染线程上解析、渲染一页，完成后在主线程上回调（失败为 null）。{@code images} 是检查过的图片。
     */
    public static void render(byte[] html, PageParser.Mode mode, String url, Map<String, byte[]> images, double scale, Consumer<RenderedPage> done)
    {
        RENDER.execute(() ->
        {
            RenderedPage page = null;
            try
            {
                PageDocument doc = PageDocument.parse(html, mode, url, ComputerViews::itemName, PageRenderer.VIEW_WIDTH);
                page = PageRenderer.render(doc, images, scale);
            }
            catch(PageDocument.Invalid e)
            {
                MrCrayfishFurnitureMod.logger().debug("Browser: page rejected ({}): {}", url, e.getMessage());
            }
            catch(Throwable t)
            {
                // 内核里的任何异常（含内存不够、栈溢出）都不能带崩游戏
                MrCrayfishFurnitureMod.logger().warn("Browser: failed to render {}: {}", url, t.toString());
            }
            RenderedPage result = page;
            Minecraft.getMinecraft().addScheduledTask(() -> done.accept(result));
        });
    }

    /** 旁观：解码服务端发来的页面（先按上限检查），渲染 */
    public static void renderSnapshot(byte[] compressed, double scale, Consumer<PageSnapshot> snapshot, Consumer<RenderedPage> done)
    {
        PageSnapshot s;
        try
        {
            s = PageSnapshot.decode(compressed);
        }
        catch(IOException e)
        {
            done.accept(null);
            return;
        }
        snapshot.accept(s);
        // 先不带图渲染；图由旁观者用自己的玩家临时地址另取（GuiBrowser.loadSpectatorImages）
        render(s.html, s.mode, s.url, new HashMap<>(), scale, done);
    }
}
