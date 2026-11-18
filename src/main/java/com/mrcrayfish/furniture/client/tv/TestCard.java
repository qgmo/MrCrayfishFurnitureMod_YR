package com.mrcrayfish.furniture.client.tv;

/**
 * 直播没开播时的测试卡画面（纯逻辑，悠然服务器魔改，服主 11-18）：照八九十年代电视台夜间停播时常见的圆形测试卡的风格，
 * 全部由程序按像素算出来（不用图片素材，不带任何商标、台标、厂家文字），渲染线程拿去建一张纹理。
 * <ul>
 * <li>灰底、白色方格线，四角各一个小圆；</li>
 * <li>中间一个大圆（白边），圆里从上到下：八色彩条（白、黄、青、绿、品红、红、蓝、黑）、六级灰阶、
 * 由粗到细的黑白频率条、底下一条黑带（频道号由画屏幕的代码用文字写在这里）；</li>
 * <li>圆心一个白色十字。</li>
 * </ul>
 * 宽高比 4:3（老电视），画的时候按电视屏幕的比例缩放。
 */
public final class TestCard
{
    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;

    static final int GRAY = 0xFF7F7F7F;
    static final int WHITE = 0xFFFFFFFF;
    static final int BLACK = 0xFF000000;
    /** 八色彩条（75% 亮度，老测试卡的习惯） */
    static final int[] BARS = {0xFFBFBFBF, 0xFFBFBF00, 0xFF00BFBF, 0xFF00BF00, 0xFFBF00BF, 0xFFBF0000, 0xFF0000BF, 0xFF101010};
    /** 频率条每一块的条纹宽度（像素，由粗到细） */
    static final int[] GRATINGS = {8, 6, 4, 3, 2, 1};

    private TestCard() {}

    /** 圆心、半径、各条带的上下边（像素），画文字的代码也要用 */
    public static final class Geometry
    {
        public final int cx, cy, r;
        public final int barsTop, grayTop, gratingTop, labelTop, labelBottom;

        Geometry(int w, int h)
        {
            cx = w / 2;
            cy = h / 2;
            r = Math.min(w, h) * 42 / 100;
            barsTop = cy - r * 3 / 4;
            grayTop = cy - r * 3 / 10;
            gratingTop = cy + r / 20;
            labelTop = cy + r * 2 / 5;
            labelBottom = cy + r * 7 / 10;
        }
    }

    public static Geometry geometry(int w, int h)
    {
        return new Geometry(w, h);
    }

    /** 算出整张图（ARGB，按行排） */
    public static int[] render(int w, int h)
    {
        int[] px = new int[w * h];
        Geometry g = new Geometry(w, h);
        int cell = Math.max(4, h / 12);
        int corner = Math.max(3, h / 10);
        for(int y = 0; y < h; y++)
        {
            for(int x = 0; x < w; x++)
            {
                int c;
                long dx = x - g.cx, dy = y - g.cy;
                long d2 = dx * dx + dy * dy;
                long r2 = (long) g.r * g.r;
                long inner = (long) (g.r - 2) * (g.r - 2);
                if(d2 <= inner)
                    c = insideCircle(x, y, g);
                else if(d2 <= r2)
                    c = WHITE;
                else
                    c = background(x, y, w, h, cell, corner);
                px[y * w + x] = c;
            }
        }
        return px;
    }

    private static int background(int x, int y, int w, int h, int cell, int corner)
    {
        if(x == 0 || y == 0 || x == w - 1 || y == h - 1)
            return BLACK;
        // 四角的小圆
        int[] cxs = {corner + 2, w - corner - 3};
        int[] cys = {corner + 2, h - corner - 3};
        for(int ccx : cxs)
        {
            for(int ccy : cys)
            {
                long dx = x - ccx, dy = y - ccy;
                long d2 = dx * dx + dy * dy;
                if(d2 <= (long) corner * corner)
                    return d2 >= (long) (corner - 1) * (corner - 1) ? WHITE : (((x + y) & 1) == 0 ? BLACK : WHITE);
            }
        }
        // 方格线
        if((x - w / 2) % cell == 0 || (y - h / 2) % cell == 0)
            return WHITE;
        return GRAY;
    }

    private static int insideCircle(int x, int y, Geometry g)
    {
        // 圆心十字
        if((x == g.cx || y == g.cy) && Math.abs(x - g.cx) <= g.r / 6 && Math.abs(y - g.cy) <= g.r / 6)
            return WHITE;
        int left = g.cx - g.r, span = 2 * g.r;
        int u = x - left;
        if(y < g.barsTop)
            return GRAY;
        if(y < g.grayTop)
            return BARS[Math.min(BARS.length - 1, Math.max(0, u * BARS.length / span))];
        if(y < g.gratingTop)
        {
            int step = Math.min(5, Math.max(0, u * 6 / span));
            int v = step * 255 / 5;
            return 0xFF000000 | v << 16 | v << 8 | v;
        }
        if(y < g.labelTop)
        {
            int block = Math.min(GRATINGS.length - 1, Math.max(0, u * GRATINGS.length / span));
            int period = GRATINGS[block];
            return (u / period) % 2 == 0 ? WHITE : BLACK;
        }
        if(y < g.labelBottom)
            return BLACK;
        return GRAY;
    }
}
