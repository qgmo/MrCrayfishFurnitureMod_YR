package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.computer.BrowserHistory;
import com.mrcrayfish.furniture.computer.CookieJar;
import com.mrcrayfish.furniture.computer.ItemIcons;
import com.mrcrayfish.furniture.computer.PageParser;
import com.mrcrayfish.furniture.computer.PageSnapshot;
import com.mrcrayfish.furniture.computer.TypingSync;
import com.mrcrayfish.furniture.network.message.MessageComputerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Collections;
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
        /** 服务端存着的会话（操作者打开时收到；浏览器没有这台电脑的状态时按它恢复，用过就清掉） */
        @Nullable
        public BrowserHistory savedHistory;
        /**
         * 这台电脑的 Cookie（服主 11-15：存在服务端、跟着电脑走）：成为操作者时服务端发来，浏览器请求时带上、收到的新 Cookie 存进来并交回服务端。
         * 不是操作者（旁观、关了界面）时为 null。
         */
        @Nullable
        public CookieJar cookies;
        /** 操作者画板里画的（画板编号 → 像素；旁观时显示）；换页面清空 */
        public final Map<Integer, CanvasView> canvases = new HashMap<>();
        /** 主页设置（操作者打开时服务端发来）：收到了没有、服务器默认、这台电脑的（没设为 null） */
        public boolean homeKnown;
        public String defaultHome = com.mrcrayfish.furniture.computer.UrlInput.HOME;
        @Nullable
        public String home;

        /** 这台电脑现在的主页；还不知道时为 null */
        @Nullable
        public String effectiveHome()
        {
            return homeKnown ? com.mrcrayfish.furniture.computer.HomePage.effective(home, defaultHome) : null;
        }
    }

    /** 旁观时看到的一张画板 */
    public static final class CanvasView
    {
        public final int width;
        public final int height;
        public final byte[] pixels;
        /** 每收到一张加一（界面据此重做贴图） */
        public final int version;

        CanvasView(int width, int height, byte[] pixels, int version)
        {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.version = version;
        }
    }

    private static int canvasVersion;

    /** 这台电脑的浏览器（按服务器 + 维度 + 位置，没有就建一个） */
    public static Browser browser(BlockPos pos)
    {
        return BROWSERS.computeIfAbsent(key(pos), k -> new Browser(pos.toImmutable()));
    }

    /** 这台电脑排这一页比标准频率多等多久（毫秒；按客户端世界里这台电脑的主板数据，找不到电脑时不等） */
    static long extraLayoutMillis(BlockPos pos, int pageBytes)
    {
        net.minecraft.world.World w = Minecraft.getMinecraft().world;
        net.minecraft.tileentity.TileEntity te = w == null ? null : w.getTileEntity(pos);
        return te instanceof com.mrcrayfish.furniture.tileentity.TileEntityComputer ? ((com.mrcrayfish.furniture.tileentity.TileEntityComputer) te).extraLayoutMillis(pageBytes) : 0;
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
            v.canvases.clear();
        }
        else if(kind == MessageComputerView.CLEAR)
        {
            v.page = null;
            v.scroll = 0;
            v.version++;
            v.typed.clear();
            v.canvases.clear();
        }
        else if(kind == MessageComputerView.CANVAS)
        {
            if(data.length < 4 || (!v.canvases.containsKey(scroll) && v.canvases.size() >= PageDocument.MAX_CONTROLS))
                return;
            int w = (data[0] & 0xFF) << 8 | (data[1] & 0xFF);
            int h = (data[2] & 0xFF) << 8 | (data[3] & 0xFF);
            byte[] px = com.mrcrayfish.furniture.computer.PixelCanvas.fromSync(java.util.Arrays.copyOfRange(data, 4, data.length), w, h);
            if(px != null)
                v.canvases.put(scroll, new CanvasView(w, h, px, ++canvasVersion));
        }
        else if(kind == MessageComputerView.HOME)
        {
            String[] h = com.mrcrayfish.furniture.computer.HomePage.decode(data);
            if(h != null)
            {
                v.defaultHome = h[0];
                v.home = h[1];
                v.homeKnown = true;
            }
        }
        else if(kind == MessageComputerView.HISTORY)
        {
            v.savedHistory = BrowserHistory.decode(data);
        }
        else if(kind == MessageComputerView.COOKIES)
        {
            CookieJar jar = CookieJar.decode(data, false, System.currentTimeMillis());
            v.cookies = jar == null ? new CookieJar() : jar;
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

    /** 物品写法 → 本地化名字 */
    @Nullable
    public static String itemName(String id)
    {
        ItemStack s = itemStack(id);
        return s.isEmpty() ? null : s.getDisplayName();
    }

    /** 网页里 {@code data-yr-lang} 的翻译：只翻有的键，不带参数 */
    @Nullable
    public static String translate(String key)
    {
        return I18n.hasKey(key) ? I18n.format(key) : null;
    }

    /** 解析过的物品写法（画图标每帧都要用）；渲染线程与主线程都会调 */
    private static final Map<String, ItemStack> STACKS = Collections.synchronizedMap(new LinkedHashMap<String, ItemStack>(64, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest)
        {
            return size() > 256;
        }
    });

    /** 物品写法 {@code 注册名[@数据值][{SNBT}]}（见 {@link ItemIcons}）→ 物品；认不出来为空 */
    public static ItemStack itemStack(String id)
    {
        ItemStack cached = STACKS.get(id);
        if(cached != null)
            return cached;
        ItemStack s = parseItem(id);
        STACKS.put(id, s);
        return s;
    }

    static ItemStack parseItem(String id)
    {
        try
        {
            String base = id;
            NBTTagCompound tag = null;
            int brace = id.indexOf('{');
            if(brace >= 0)
            {
                if(id.length() - brace > ItemIcons.MAX_NBT)
                    return ItemStack.EMPTY;
                tag = ItemIcons.filter(JsonToNBT.getTagFromJson(id.substring(brace)));
                base = id.substring(0, brace);
            }
            String name = base;
            int meta = 0;
            int at = base.indexOf('@');
            if(at >= 0)
            {
                name = base.substring(0, at);
                meta = Integer.parseInt(base.substring(at + 1));
            }
            Item item = Item.REGISTRY.getObject(new ResourceLocation(name));
            if(item == null || meta < 0 || meta > Short.MAX_VALUE)
                return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(item, 1, meta);
            if(tag != null)
                stack.setTagCompound(tag);
            return stack;
        }
        catch(RuntimeException | NBTException e)
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
                PageDocument doc = PageDocument.parse(html, mode, url, ComputerViews::itemName, ComputerViews::translate, PageRenderer.VIEW_WIDTH);
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
