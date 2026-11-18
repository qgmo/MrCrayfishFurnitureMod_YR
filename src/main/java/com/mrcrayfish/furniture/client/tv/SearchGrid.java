package com.mrcrayfish.furniture.client.tv;

/**
 * 搜索结果的排列（纯逻辑，悠然服务器魔改，服主 11-18：按窗口大小与 GUI 缩放自动排，窗口大时一行多列、封面变大，窗口小时少列）。
 * 输入是界面的宽高（Minecraft 的 GUI 坐标，已经按 GUI 缩放算过）和一行字的高度，不写死尺寸：
 * <ul>
 * <li>每张卡片最窄 = 可用宽度的五分之一，但在 {@value #MIN_CARD} 到 {@value #MAX_CARD} 之间；列数 = 放得下几张，1 到 {@value #MAX_COLUMNS} 列；</li>
 * <li>卡片按列数平分可用宽度；封面 16:9；下面四行字：标题两行、UP 主 / 主播一行、播放量 / 围观人数一行。</li>
 * </ul>
 */
public final class SearchGrid
{
    public static final int MARGIN = 8;
    public static final int GAP = 6;
    public static final int MIN_CARD = 110;
    public static final int MAX_CARD = 200;
    public static final int MAX_COLUMNS = 8;
    /** 上方标题、输入框、状态行占的高度 */
    public static final int HEADER = 62;
    public static final int TEXT_LINES = 4;

    public final int columns;
    public final int cardWidth;
    public final int coverHeight;
    public final int cardHeight;
    public final int left;
    public final int top;
    public final int viewHeight;
    public final int lineHeight;

    private SearchGrid(int columns, int cardWidth, int coverHeight, int cardHeight, int left, int top, int viewHeight, int lineHeight)
    {
        this.columns = columns;
        this.cardWidth = cardWidth;
        this.coverHeight = coverHeight;
        this.cardHeight = cardHeight;
        this.left = left;
        this.top = top;
        this.viewHeight = viewHeight;
        this.lineHeight = lineHeight;
    }

    public static SearchGrid compute(int screenWidth, int screenHeight, int lineHeight)
    {
        int available = Math.max(1, screenWidth - 2 * MARGIN);
        int minCard = Math.max(MIN_CARD, Math.min(MAX_CARD, available / 5));
        int columns = Math.max(1, Math.min(MAX_COLUMNS, (available + GAP) / (minCard + GAP)));
        int cardWidth = Math.max(1, (available - GAP * (columns - 1)) / columns);
        int coverHeight = cardWidth * 9 / 16;
        int cardHeight = coverHeight + 3 + TEXT_LINES * lineHeight + 2;
        int viewHeight = Math.max(1, screenHeight - HEADER - MARGIN);
        return new SearchGrid(columns, cardWidth, coverHeight, cardHeight, MARGIN, HEADER, viewHeight, lineHeight);
    }

    /** 第 index 张卡片的左上角（不算滚动），{x, y} */
    public int[] cardPos(int index)
    {
        int row = index / columns, col = index % columns;
        return new int[]{left + col * (cardWidth + GAP), top + row * (cardHeight + GAP)};
    }

    /** n 张卡片一共多高 */
    public int contentHeight(int n)
    {
        int rows = (n + columns - 1) / columns;
        return rows <= 0 ? 0 : rows * (cardHeight + GAP) - GAP;
    }

    /** 滚动的上限 */
    public int maxScroll(int n)
    {
        return Math.max(0, contentHeight(n) - viewHeight);
    }

    /** 滚到离底部不到一排时该加载下一页了 */
    public boolean nearBottom(int scroll, int n)
    {
        return scroll + viewHeight >= contentHeight(n) - cardHeight;
    }

    /** 点在 (x, y)（界面坐标）上的是第几张；没有返回 -1 */
    public int hit(int x, int y, int scroll, int n)
    {
        if(y < top || y >= top + viewHeight || x < left)
            return -1;
        int cy = y - top + scroll;
        int col = (x - left) / (cardWidth + GAP);
        int row = cy / (cardHeight + GAP);
        if(col >= columns || (x - left) % (cardWidth + GAP) >= cardWidth || cy % (cardHeight + GAP) >= cardHeight)
            return -1;
        int index = row * columns + col;
        return index < n ? index : -1;
    }
}
