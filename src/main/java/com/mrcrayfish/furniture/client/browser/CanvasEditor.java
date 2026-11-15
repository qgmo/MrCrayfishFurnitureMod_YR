package com.mrcrayfish.furniture.client.browser;

import com.mrcrayfish.furniture.computer.PixelCanvas;
import moe.qingu.youran.api.image.DyePng;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

/**
 * 画板编辑器（服主 11-15）：操作者点网页里的画板时盖在页面上打开，像素放大编辑。工具：铅笔、橡皮、油漆桶、直线、取色；固定 16 种染料色；撤销、清空（要再点一次确认）。
 * 左键用当前工具，右键取色；滚轮上下移、Shift + 滚轮左右移、Ctrl + 滚轮缩放；B / E / G / L / I 切工具，Ctrl+Z 撤销，Esc / 回车关掉。
 * 画的东西直接改表单里的那张画板（{@link FormState}），关掉编辑器、翻页、关界面都留着。
 */
final class CanvasEditor
{
    enum Tool
    {
        PENCIL("pencil", Keyboard.KEY_B), ERASER("eraser", Keyboard.KEY_E), FILL("fill", Keyboard.KEY_G), LINE("line", Keyboard.KEY_L), PICKER("picker", Keyboard.KEY_I);

        final String key;
        final int hotkey;

        Tool(String key, int hotkey)
        {
            this.key = key;
            this.hotkey = hotkey;
        }
    }

    static final double[] ZOOMS = {0.5, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32};
    private static final int BG = 0xFF3A3D42;
    private static final int BAR = 0xFFE8EAED;
    private static final int TEXT = 0xFF202124;

    final int control;
    final PixelCanvas canvas;
    private final FontRenderer font;
    private final CanvasTexture texture = new CanvasTexture();
    Tool tool = Tool.PENCIL;
    /** 当前颜色（1–16） */
    int color = 16;
    private int zoomIndex;
    /** 画板左上角在视口里的偏移（画板像素） */
    private double panX, panY;
    private boolean confirmClear;
    // 正在画的一笔
    private boolean drawing;
    private int lastX, lastY;
    private int lineX = -1, lineY = -1, lineToX, lineToY;
    // 布局（界面坐标）
    private int x, y, w, h;
    private int cx, cy, cw, ch;
    private final int[][] toolBtn = new int[Tool.values().length][4];
    private final int[] undoBtn = new int[4], clearBtn = new int[4], zoomOutBtn = new int[4], zoomInBtn = new int[4], doneBtn = new int[4];
    private final int[][] swatch = new int[16][4];
    boolean closed;

    CanvasEditor(int control, PixelCanvas canvas, FontRenderer font, int areaW, int areaH)
    {
        this.control = control;
        this.canvas = canvas;
        this.font = font;
        // 默认：放得下的最大倍数
        zoomIndex = 0;
        for(int i = 0; i < ZOOMS.length; i++)
        {
            if(canvas.width * ZOOMS[i] <= areaW - 12 && canvas.height * ZOOMS[i] <= areaH - 40)
                zoomIndex = i;
        }
    }

    double zoom()
    {
        return ZOOMS[zoomIndex];
    }

    void dispose()
    {
        texture.dispose();
    }

    // ---- 画 ----

    void draw(int ax, int ay, int aw, int ah, int mouseX, int mouseY, float zLevel)
    {
        x = ax;
        y = ay;
        w = aw;
        h = ah;
        Gui.drawRect(x, y, x + w, y + h, BG);
        // 工具条
        Gui.drawRect(x, y, x + w, y + 30, BAR);
        int bx = x + 3;
        for(Tool t : Tool.values())
        {
            String label = I18n.format("cfm.browser.canvas.tool." + t.key);
            bx = button(toolBtn[t.ordinal()], bx, y + 2, label, tool == t, true, mouseX, mouseY) + 2;
        }
        bx += 4;
        bx = button(undoBtn, bx, y + 2, I18n.format("cfm.browser.canvas.undo"), false, canvas.canUndo(), mouseX, mouseY) + 2;
        bx = button(clearBtn, bx, y + 2, I18n.format(confirmClear ? "cfm.browser.canvas.clear_confirm" : "cfm.browser.canvas.clear"), confirmClear, !canvas.isBlank(), mouseX, mouseY) + 6;
        bx = button(zoomOutBtn, bx, y + 2, "−", false, zoomIndex > 0, mouseX, mouseY) + 1;
        // 倍数按屏幕像素说（界面坐标乘界面缩放）：1 倍就是一个像素占屏幕上一个点
        String z = I18n.format("cfm.browser.canvas.zoom", formatZoom(zoom() * new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()).getScaleFactor()));
        font.drawString(z, bx + 2, y + 5, TEXT);
        bx += font.getStringWidth(z) + 5;
        button(zoomInBtn, bx, y + 2, "+", false, zoomIndex < ZOOMS.length - 1, mouseX, mouseY);
        String done = I18n.format("cfm.browser.canvas.done");
        button(doneBtn, x + w - 6 - font.getStringWidth(done) - 8, y + 2, done, false, true, mouseX, mouseY);
        // 调色板
        int sx = x + 3;
        int sy = y + 17;
        for(int i = 0; i < 16; i++)
        {
            int[] r = swatch[i];
            r[0] = sx + i * 13;
            r[1] = sy;
            r[2] = 12;
            r[3] = 11;
            boolean sel = tool != Tool.ERASER && color == i + 1;
            Gui.drawRect(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, sel ? 0xFF1A73E8 : 0xFF80868B);
            Gui.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], DyePng.argb(i + 1));
            if(sel)
                Gui.drawRect(r[0] + 4, r[1] + 4, r[0] + 8, r[1] + 7, (DyePng.DYE_RGB[i] & 0xFF) + ((DyePng.DYE_RGB[i] >> 8) & 0xFF) > 300 ? 0xFF000000 : 0xFFFFFFFF);
        }
        String title = I18n.format("cfm.browser.canvas.title", canvas.width, canvas.height);
        font.drawString(title, sx + 16 * 13 + 6, sy + 2, 0xFF5F6368);
        // 画板
        cx = x + 4;
        cy = y + 32;
        cw = w - 8;
        ch = h - 32 - 12;
        double zm = zoom();
        clampPan();
        double ox = cx + Math.max(0, (cw - canvas.width * zm) / 2) - (canvas.width * zm > cw ? panX * zm : 0);
        double oy = cy + Math.max(0, (ch - canvas.height * zm) / 2) - (canvas.height * zm > ch ? panY * zm : 0);
        startScissor(cx, cy, cw, ch);
        // 透明的地方：浅色棋盘格（每格 8 个画板像素，最多画这么多格）
        int cell = Math.max(1, (int) Math.ceil(8 * zm));
        int x0 = (int) Math.max(cx, ox), y0 = (int) Math.max(cy, oy);
        int x1 = (int) Math.min(cx + cw, ox + canvas.width * zm), y1 = (int) Math.min(cy + ch, oy + canvas.height * zm);
        Gui.drawRect(x0, y0, x1, y1, 0xFFFFFFFF);
        if((x1 - x0) / cell * ((y1 - y0) / cell) < 4000)
        {
            for(int gy = (int) oy; gy < y1; gy += cell)
            {
                for(int gx = (int) ox; gx < x1; gx += cell)
                {
                    if((((gx - (int) ox) / cell) + ((gy - (int) oy) / cell)) % 2 == 1)
                        Gui.drawRect(Math.max(gx, x0), Math.max(gy, y0), Math.min(gx + cell, x1), Math.min(gy + cell, y1), 0xFFE4E4E4);
                }
            }
        }
        texture.update(canvas, canvas.width, canvas.height, canvas.version(), canvas.pixels());
        texture.draw(ox, oy, canvas.width * zm, canvas.height * zm, zLevel);
        // 网格（放大到 6 倍以上）
        if(zm >= 6)
        {
            for(int i = 0; i <= canvas.width; i++)
            {
                int gx = (int) Math.round(ox + i * zm);
                if(gx >= cx && gx <= cx + cw)
                    Gui.drawRect(gx, y0, gx + 1, y1, 0x30000000);
            }
            for(int j = 0; j <= canvas.height; j++)
            {
                int gy = (int) Math.round(oy + j * zm);
                if(gy >= cy && gy <= cy + ch)
                    Gui.drawRect(x0, gy, x1, gy + 1, 0x30000000);
            }
        }
        // 直线预览
        if(lineX >= 0)
        {
            PixelCanvas preview = new PixelCanvas(canvas.width, canvas.height);
            preview.line(lineX, lineY, lineToX, lineToY, 1);
            byte[] px = preview.pixels();
            int col = DyePng.argb(color);
            for(int i = 0; i < px.length; i++)
            {
                if(px[i] != 0)
                {
                    double px0 = ox + (i % canvas.width) * zm, py0 = oy + (i / canvas.width) * zm;
                    Gui.drawRect((int) Math.floor(px0), (int) Math.floor(py0), (int) Math.ceil(px0 + zm), (int) Math.ceil(py0 + zm), col);
                }
            }
        }
        // 指着的格子
        int[] p = pixelAt(mouseX, mouseY);
        if(p != null)
        {
            int hx = (int) Math.floor(ox + p[0] * zm), hy = (int) Math.floor(oy + p[1] * zm);
            int hz = Math.max(1, (int) Math.ceil(zm));
            Gui.drawRect(hx, hy, hx + hz, hy + 1, 0xFF1A73E8);
            Gui.drawRect(hx, hy + hz - 1, hx + hz, hy + hz, 0xFF1A73E8);
            Gui.drawRect(hx, hy, hx + 1, hy + hz, 0xFF1A73E8);
            Gui.drawRect(hx + hz - 1, hy, hx + hz, hy + hz, 0xFF1A73E8);
        }
        endScissor();
        // 状态行
        String status = p == null ? I18n.format("cfm.browser.canvas.help") : I18n.format("cfm.browser.canvas.pos", p[0] + 1, p[1] + 1);
        font.drawString(font.trimStringToWidth(status, w - 8), x + 4, y + h - 10, 0xFFC8CCD2);
    }

    private static String formatZoom(double z)
    {
        return z == Math.floor(z) ? String.valueOf((int) z) : String.valueOf(z);
    }

    private int button(int[] r, int bx, int by, String label, boolean active, boolean enabled, int mouseX, int mouseY)
    {
        int bw = font.getStringWidth(label) + 8;
        r[0] = bx;
        r[1] = by;
        r[2] = bw;
        r[3] = 12;
        boolean hover = enabled && in(r, mouseX, mouseY);
        int bg = !enabled ? 0xFFD0D3D7 : active ? (r == clearBtn ? 0xFFD93025 : 0xFF1A73E8) : hover ? 0xFFC9D7F2 : 0xFFF8F9FA;
        Gui.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF80868B);
        Gui.drawRect(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, bg);
        font.drawString(label, r[0] + 4, r[1] + 2, !enabled ? 0xFF9AA0A6 : active ? 0xFFFFFFFF : TEXT);
        return bx + bw;
    }

    private static boolean in(int[] r, int mx, int my)
    {
        return r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void clampPan()
    {
        double zm = zoom();
        double maxX = Math.max(0, canvas.width - cw / zm);
        double maxY = Math.max(0, canvas.height - ch / zm);
        panX = Math.max(0, Math.min(maxX, panX));
        panY = Math.max(0, Math.min(maxY, panY));
    }

    /** 鼠标指着画板的哪个像素；不在画板上为 null */
    int[] pixelAt(int mouseX, int mouseY)
    {
        if(mouseX < cx || mouseY < cy || mouseX >= cx + cw || mouseY >= cy + ch)
            return null;
        double zm = zoom();
        double ox = cx + Math.max(0, (cw - canvas.width * zm) / 2) - (canvas.width * zm > cw ? panX * zm : 0);
        double oy = cy + Math.max(0, (ch - canvas.height * zm) / 2) - (canvas.height * zm > ch ? panY * zm : 0);
        int px = (int) Math.floor((mouseX + 0.5 - ox) / zm);
        int py = (int) Math.floor((mouseY + 0.5 - oy) / zm);
        return canvas.inside(px, py) ? new int[]{px, py} : null;
    }

    // ---- 输入 ----

    /** 编辑器开着时的点击（都归它） */
    void mouseClicked(int mx, int my, int button)
    {
        boolean wasConfirm = confirmClear;
        confirmClear = false;
        if(button == 0)
        {
            for(Tool t : Tool.values())
            {
                if(in(toolBtn[t.ordinal()], mx, my))
                {
                    tool = t;
                    return;
                }
            }
            for(int i = 0; i < 16; i++)
            {
                if(in(swatch[i], mx, my))
                {
                    color = i + 1;
                    if(tool == Tool.ERASER || tool == Tool.PICKER)
                        tool = Tool.PENCIL;
                    return;
                }
            }
            if(in(undoBtn, mx, my))
            {
                canvas.undo();
                return;
            }
            if(in(clearBtn, mx, my))
            {
                // 清空要再点一次确认（用户画的东西不能一下子没了；清了也能撤销）
                if(wasConfirm)
                    canvas.clear();
                else
                    confirmClear = !canvas.isBlank();
                return;
            }
            if(in(zoomOutBtn, mx, my))
            {
                zoomBy(-1);
                return;
            }
            if(in(zoomInBtn, mx, my))
            {
                zoomBy(1);
                return;
            }
            if(in(doneBtn, mx, my))
            {
                closed = true;
                return;
            }
        }
        int[] p = pixelAt(mx, my);
        if(p == null)
            return;
        if(button == 1)
        {
            pick(p);
            return;
        }
        if(button != 0)
            return;
        switch(tool)
        {
            case PENCIL:
            case ERASER:
                canvas.beginStroke();
                canvas.set(p[0], p[1], paint());
                drawing = true;
                lastX = p[0];
                lastY = p[1];
                break;
            case FILL:
                canvas.beginStroke();
                canvas.fill(p[0], p[1], color);
                break;
            case LINE:
                lineX = lineToX = p[0];
                lineY = lineToY = p[1];
                break;
            case PICKER:
                pick(p);
                break;
            default:
                break;
        }
    }

    private int paint()
    {
        return tool == Tool.ERASER ? 0 : color;
    }

    private void pick(int[] p)
    {
        int c = canvas.get(p[0], p[1]);
        if(c == 0)
        {
            tool = Tool.ERASER;
        }
        else
        {
            color = c;
            tool = Tool.PENCIL;
        }
    }

    void mouseDragged(int mx, int my)
    {
        int[] p = pixelAtClamped(mx, my);
        if(drawing && p != null)
        {
            canvas.line(lastX, lastY, p[0], p[1], paint());
            lastX = p[0];
            lastY = p[1];
        }
        else if(lineX >= 0 && p != null)
        {
            lineToX = p[0];
            lineToY = p[1];
        }
    }

    void mouseReleased(int mx, int my)
    {
        drawing = false;
        if(lineX >= 0)
        {
            int[] p = pixelAtClamped(mx, my);
            if(p != null)
            {
                lineToX = p[0];
                lineToY = p[1];
            }
            canvas.beginStroke();
            canvas.line(lineX, lineY, lineToX, lineToY, color);
            lineX = -1;
        }
    }

    /** 拖到画板外时按边上的像素算 */
    private int[] pixelAtClamped(int mx, int my)
    {
        int[] p = pixelAt(Math.max(cx, Math.min(cx + cw - 1, mx)), Math.max(cy, Math.min(cy + ch - 1, my)));
        if(p != null)
            return p;
        double zm = zoom();
        double ox = cx + Math.max(0, (cw - canvas.width * zm) / 2) - (canvas.width * zm > cw ? panX * zm : 0);
        double oy = cy + Math.max(0, (ch - canvas.height * zm) / 2) - (canvas.height * zm > ch ? panY * zm : 0);
        int px = (int) Math.floor((mx - ox) / zm);
        int py = (int) Math.floor((my - oy) / zm);
        return new int[]{Math.max(0, Math.min(canvas.width - 1, px)), Math.max(0, Math.min(canvas.height - 1, py))};
    }

    void wheel(int dir)
    {
        if(GuiScreen.isCtrlKeyDown())
            zoomBy(dir > 0 ? 1 : -1);
        else if(GuiScreen.isShiftKeyDown())
            panX -= dir * 40 / zoom();
        else
            panY -= dir * 40 / zoom();
        clampPan();
    }

    private void zoomBy(int d)
    {
        double before = zoom();
        // 以画板中心为准缩放
        double centerX = panX + cw / before / 2, centerY = panY + ch / before / 2;
        zoomIndex = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + d));
        panX = centerX - cw / zoom() / 2;
        panY = centerY - ch / zoom() / 2;
        clampPan();
    }

    /** 按键；处理了返回 true */
    boolean keyTyped(int key)
    {
        if(key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
        {
            closed = true;
            return true;
        }
        if(GuiScreen.isCtrlKeyDown() && key == Keyboard.KEY_Z)
        {
            canvas.undo();
            return true;
        }
        for(Tool t : Tool.values())
        {
            if(key == t.hotkey && !GuiScreen.isCtrlKeyDown())
            {
                tool = t;
                return true;
            }
        }
        switch(key)
        {
            case Keyboard.KEY_LEFT: panX -= 20 / zoom(); clampPan(); return true;
            case Keyboard.KEY_RIGHT: panX += 20 / zoom(); clampPan(); return true;
            case Keyboard.KEY_UP: panY -= 20 / zoom(); clampPan(); return true;
            case Keyboard.KEY_DOWN: panY += 20 / zoom(); clampPan(); return true;
            case Keyboard.KEY_EQUALS:
            case Keyboard.KEY_ADD: zoomBy(1); return true;
            case Keyboard.KEY_MINUS:
            case Keyboard.KEY_SUBTRACT: zoomBy(-1); return true;
            default: return true;
        }
    }

    private static void startScissor(int x, int y, int w, int h)
    {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
        int f = sr.getScaleFactor();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(x * f, mc.displayHeight - (y + h) * f, Math.max(0, w * f), Math.max(0, h * f));
    }

    private static void endScissor()
    {
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }
}
