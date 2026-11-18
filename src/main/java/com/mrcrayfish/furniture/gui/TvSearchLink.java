package com.mrcrayfish.furniture.gui;

/**
 * 频道设置界面与搜索界面之间的来回（纯逻辑，悠然服务器魔改，服主 11-18：搜索改成单独的界面，不让 JEI 的物品列表盖住）。
 * <p>
 * 搜索是一个普通的 GuiScreen，从设置界面用 {@code displayGuiScreen} 打开，返回时再显示原来那个设置界面实例。
 * 1.12.2 换界面时不发 {@code CPacketCloseWindow}，服务端的 Container 照样开着；但客户端会调旧界面的
 * {@code GuiContainer.onGuiClosed} → {@code Container.onContainerClosed}，把鼠标上拿着的物品在客户端“扔掉”（服务端没扔，两边就对不上了）。
 * 所以设置界面关的时候要先问这里：是去搜索的就不走容器关闭（{@link Close#KEEP_FOR_SEARCH}），否则照常（{@link Close#CLOSE_CONTAINER}）。
 */
public final class TvSearchLink
{
    public enum Close
    {
        /** 照常关：客户端的容器关闭（鼠标上的物品照原版处理） */
        CLOSE_CONTAINER,
        /** 去搜索界面：容器留着，什么都不做 */
        KEEP_FOR_SEARCH
    }

    private int field = -1;
    private boolean switching;
    private boolean searching;

    /** 点了第 field 格（共 fields 格）的“搜索”：可以去就返回 true（调用方接着打开搜索界面） */
    public boolean begin(int field, int fields)
    {
        if(field < 0 || field >= fields || searching || switching)
            return false;
        this.field = field;
        this.switching = true;
        return true;
    }

    /** 设置界面的 onGuiClosed */
    public Close settingsClosed()
    {
        if(switching)
        {
            switching = false;
            searching = true;
            return Close.KEEP_FOR_SEARCH;
        }
        searching = false;
        field = -1;
        return Close.CLOSE_CONTAINER;
    }

    /** 搜索界面打开前出了岔子（例如被别的事件拦住）：当作没去 */
    public void cancel()
    {
        switching = false;
        searching = false;
        field = -1;
    }

    /** 回到了设置界面（点了结果、Esc、“返回”） */
    public void returned()
    {
        searching = false;
        switching = false;
        field = -1;
    }

    /** 搜索界面被别的原因关掉、没有回到设置界面（服务端关了容器、断线）：设置界面不再算开着 */
    public void searchClosedElsewhere()
    {
        searching = false;
        switching = false;
        field = -1;
    }

    /** 正在搜哪一格（-1 表示没在搜） */
    public int field()
    {
        return searching || switching ? field : -1;
    }

    public boolean searching()
    {
        return searching;
    }

    /** 保存的结果能不能交给这个设置界面：它在显示，或者玩家正在它打开的搜索界面里 */
    public boolean acceptsResult(boolean settingsShown)
    {
        return settingsShown || searching;
    }
}
