package com.mrcrayfish.furniture.gui;

import com.mrcrayfish.furniture.client.tv.BiliSearch;
import com.mrcrayfish.furniture.client.tv.CoverCache;
import com.mrcrayfish.furniture.client.tv.SearchGrid;
import com.mrcrayfish.furniture.client.tv.TvSearchState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道设置界面的搜索的内容（悠然服务器魔改，服主 11-18），由单独的搜索界面 {@link GuiTVSearch} 铺满整个窗口画：
 * 上面是搜索框、“视频 / 直播间”切换、返回；下面的结果按窗口大小排列（{@link SearchGrid}），滚轮滚动，滚到底自动加载下一页。
 * 每个结果只显示封面、标题、UP 主（主播）名字（前面一个人像图标）、播放量（直播间是围观人数）。点一个结果就把它的写法填进这一格并回到设置界面，
 * 还要玩家点“保存”才生效。输的字、搜出来的结果放在 {@link TvSearchState} 里，关掉再开（同一次游戏内）都还在。
 */
public class GuiTVSearchPanel
{
    /** 设置界面那边 */
    public interface Host
    {
        /** 点了一个结果：写法填进第几格由设置界面记着 */
        void onPick(String channel);

        /** Esc 或“返回” */
        void onClose();
    }

    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_DIM = 0xA0A0A0;

    private final Minecraft mc;
    private final Host host;
    private final int channelNumber;
    private int width, height;
    private GuiTextField input;
    private GuiIconButton searchButton, videoTab, liveTab, backButton;
    private final List<GuiButton> buttons = new ArrayList<>();
    private SearchGrid grid;

    public GuiTVSearchPanel(Minecraft mc, Host host, int channelNumber)
    {
        this.mc = mc;
        this.host = host;
        this.channelNumber = channelNumber;
    }

    /** 打开、窗口大小或 GUI 缩放变了时重新排 */
    public void init(int width, int height)
    {
        this.width = width;
        this.height = height;
        FontRenderer font = mc.fontRenderer;
        grid = SearchGrid.compute(width, height, font.FONT_HEIGHT + 1);
        buttons.clear();
        int right = width - SearchGrid.MARGIN;
        backButton = new GuiIconButton(0, right - 48, 6, 48, 14, GuiIconButton.Icon.NONE, I18n.format("cfm.tv.search.back"));
        liveTab = new GuiIconButton(1, right - 48 - 4 - 56, 6, 56, 14, GuiIconButton.Icon.NONE, I18n.format(BiliSearch.Type.LIVE.langKey));
        videoTab = new GuiIconButton(2, right - 48 - 4 - 56 - 2 - 48, 6, 48, 14, GuiIconButton.Icon.NONE, I18n.format(BiliSearch.Type.VIDEO.langKey));
        searchButton = new GuiIconButton(3, right - 60, 25, 60, 16, GuiIconButton.Icon.MAGNIFIER, I18n.format("cfm.tv.search.button"));
        buttons.add(backButton);
        buttons.add(liveTab);
        buttons.add(videoTab);
        buttons.add(searchButton);
        String text = input == null ? TvSearchState.input() : input.getText();
        input = new GuiTextField(10, font, SearchGrid.MARGIN + 1, 26, Math.max(40, right - 64 - SearchGrid.MARGIN - 2), 14);
        input.setMaxStringLength(TvSearchState.MAX_QUERY);
        input.setText(text);
        input.setFocused(true);
        clampScroll();
    }

    private TvSearchState.Results results()
    {
        return TvSearchState.results(TvSearchState.type());
    }

    private void clampScroll()
    {
        TvSearchState.Results r = results();
        r.scroll = Math.max(0, Math.min(grid.maxScroll(r.items.size()), r.scroll));
    }

    public void update()
    {
        input.updateCursorCounter();
        TvSearchState.Results r = results();
        clampScroll();
        // 滚到底了：加载下一页
        if(!r.loading && r.error == null && r.hasMore() && grid.nearBottom(r.scroll, r.items.size()))
            TvSearchState.loadMore();
    }

    public void keyTyped(char c, int key)
    {
        if(key == Keyboard.KEY_ESCAPE)
        {
            host.onClose();
            return;
        }
        if(key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
        {
            TvSearchState.search();
            return;
        }
        if(input.textboxKeyTyped(c, key))
            TvSearchState.setInput(input.getText());
    }

    public void mouseClicked(int x, int y, int button)
    {
        if(button == 0)
        {
            for(GuiButton b : buttons)
            {
                if(b.mousePressed(mc, x, y))
                {
                    b.playPressSound(mc.getSoundHandler());
                    pressed(b);
                    return;
                }
            }
        }
        input.mouseClicked(x, y, button);
        TvSearchState.Results r = results();
        int index = grid.hit(x, y, r.scroll, r.items.size());
        if(button == 0 && index >= 0)
            host.onPick(r.items.get(index).channel);
    }

    private void pressed(GuiButton b)
    {
        if(b == backButton)
        {
            host.onClose();
        }
        else if(b == searchButton)
        {
            TvSearchState.setInput(input.getText());
            TvSearchState.search();
        }
        else if(b == videoTab || b == liveTab)
        {
            BiliSearch.Type t = b == videoTab ? BiliSearch.Type.VIDEO : BiliSearch.Type.LIVE;
            if(t != TvSearchState.type())
            {
                TvSearchState.setType(t);
                // 换了类型：这一类还没搜过、或搜的不是框里的字，就照框里的字搜一次（之前的结果在搜出来之前照旧显示）
                TvSearchState.Results r = results();
                String q = input.getText().trim();
                if(!q.isEmpty() && !q.equals(r.query) && !r.loading)
                    TvSearchState.search();
            }
        }
    }

    /** 滚轮（正数向上） */
    public void scroll(int wheel)
    {
        if(wheel == 0)
            return;
        TvSearchState.Results r = results();
        int step = Math.max(10, (grid.cardHeight + SearchGrid.GAP) / 2);
        r.scroll -= Integer.signum(wheel) * step;
        clampScroll();
    }

    public void draw(int mouseX, int mouseY, float partialTicks)
    {
        FontRenderer font = mc.fontRenderer;
        Gui.drawRect(0, 0, width, height, 0xE0101010);
        String title = I18n.format("cfm.tv.search.title", channelNumber);
        int titleMax = videoTab.x - SearchGrid.MARGIN - 6;
        font.drawStringWithShadow(font.trimStringToWidth(title, Math.max(10, titleMax)), SearchGrid.MARGIN, 9, COLOR_TEXT);
        videoTab.selected = TvSearchState.type() == BiliSearch.Type.VIDEO;
        liveTab.selected = TvSearchState.type() == BiliSearch.Type.LIVE;
        for(GuiButton b : buttons)
            b.drawButton(mc, mouseX, mouseY, partialTicks);
        input.drawTextBox();
        if(input.getText().isEmpty() && !input.isFocused())
            font.drawString(I18n.format("cfm.tv.search.placeholder"), input.x + 4, input.y + 3, 0x808080);
        TvSearchState.Results r = results();
        font.drawString(font.trimStringToWidth(status(r), width - 2 * SearchGrid.MARGIN), SearchGrid.MARGIN, 47, r.error != null ? 0xFF8080 : COLOR_DIM);
        drawGrid(r, mouseX, mouseY);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private String status(TvSearchState.Results r)
    {
        String text;
        if(r.loading)
            text = I18n.format("cfm.tv.search.loading");
        else if(r.error != null)
            text = I18n.format("cfm.tv.search.failed", I18n.format(r.error));
        else if(!r.query.isEmpty() && r.items.isEmpty())
            text = I18n.format("cfm.tv.search.empty");
        else if(r.query.isEmpty())
            text = I18n.format("cfm.tv.search.hint");
        else
            text = I18n.format("cfm.tv.search.pick", channelNumber);
        return text;
    }

    private void drawGrid(TvSearchState.Results r, int mouseX, int mouseY)
    {
        List<BiliSearch.Item> items = r.items;
        if(items.isEmpty())
            return;
        FontRenderer font = mc.fontRenderer;
        String lang = mc.getLanguageManager().getCurrentLanguage().getLanguageCode();
        ScaledResolution sr = new ScaledResolution(mc);
        int f = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, mc.displayHeight - (grid.top + grid.viewHeight) * f, mc.displayWidth, grid.viewHeight * f);
        int hovered = grid.hit(mouseX, mouseY, r.scroll, items.size());
        for(int i = 0; i < items.size(); i++)
        {
            int[] p = grid.cardPos(i);
            int x = p[0], y = p[1] - r.scroll;
            if(y + grid.cardHeight < grid.top || y > grid.top + grid.viewHeight)
                continue;
            BiliSearch.Item item = items.get(i);
            Gui.drawRect(x - 1, y - 1, x + grid.cardWidth + 1, y + grid.cardHeight + 1, i == hovered ? 0xFFFB7299 : 0xFF303030);
            Gui.drawRect(x, y, x + grid.cardWidth, y + grid.cardHeight, 0xFF1C1C1C);
            drawCover(item, x, y);
            int ty = y + grid.coverHeight + 3;
            List<String> lines = font.listFormattedStringToWidth(item.title, grid.cardWidth - 4);
            for(int l = 0; l < Math.min(2, lines.size()); l++)
            {
                String line = lines.get(l);
                if(l == 1 && lines.size() > 2)
                    line = font.trimStringToWidth(line, grid.cardWidth - 4 - font.getStringWidth("…")) + "…";
                font.drawString(line, x + 2, ty + l * grid.lineHeight, COLOR_TEXT);
            }
            int ay = ty + 2 * grid.lineHeight;
            TvIcons.person(x + 2, ay, 1, 0xFFB0B0B0);
            font.drawString(font.trimStringToWidth(item.author, grid.cardWidth - 4 - TvIcons.SIZE - 3), x + 2 + TvIcons.SIZE + 3, ay, COLOR_DIM);
            String count = I18n.format(item.type == BiliSearch.Type.VIDEO ? "cfm.tv.search.plays" : "cfm.tv.search.watching", BiliSearch.compact(item.count, lang));
            font.drawString(font.trimStringToWidth(count, grid.cardWidth - 4), x + 2, ay + grid.lineHeight, COLOR_DIM);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        // 滚动条
        int content = grid.contentHeight(items.size());
        if(content > grid.viewHeight)
        {
            int barX = width - 4;
            int barH = Math.max(10, grid.viewHeight * grid.viewHeight / content);
            int barY = grid.top + (grid.viewHeight - barH) * r.scroll / Math.max(1, grid.maxScroll(items.size()));
            Gui.drawRect(barX, grid.top, barX + 2, grid.top + grid.viewHeight, 0xFF303030);
            Gui.drawRect(barX, barY, barX + 2, barY + barH, 0xFFA0A0A0);
        }
    }

    /** 封面：裁成 16:9 铺满；还没下载好时画一块深色 */
    private void drawCover(BiliSearch.Item item, int x, int y)
    {
        int w = grid.cardWidth, h = grid.coverHeight;
        DynamicTexture tex = item.cover == null ? null : CoverCache.byUrl(item.cover);
        int[] size = item.cover == null ? null : CoverCache.sizeByUrl(item.cover);
        if(tex == null || size == null)
        {
            Gui.drawRect(x, y, x + w, y + h, 0xFF2A2A2A);
            return;
        }
        // 居中裁剪
        float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        float imgAspect = size[0] / (float) Math.max(1, size[1]), boxAspect = w / (float) Math.max(1, h);
        if(imgAspect > boxAspect)
        {
            float keep = boxAspect / imgAspect;
            u0 = (1 - keep) / 2;
            u1 = 1 - u0;
        }
        else if(imgAspect < boxAspect)
        {
            float keep = imgAspect / boxAspect;
            v0 = (1 - keep) / 2;
            v1 = 1 - v0;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.bindTexture(tex.getGlTextureId());
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        b.pos(x, y + h, 0).tex(u0, v1).endVertex();
        b.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        b.pos(x + w, y, 0).tex(u1, v0).endVertex();
        b.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
    }
}
