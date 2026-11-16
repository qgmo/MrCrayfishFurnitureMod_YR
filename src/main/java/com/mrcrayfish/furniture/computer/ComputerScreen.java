package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

/**
 * 一台电脑在世界里屏幕上显示的缩略图（服务端状态，纯逻辑；服主 11-16）。只在内存，不进存档。
 * <ul>
 * <li>开着界面的人上传的缩略图按 {@link ScreenVote} 定下一帧；没人开着界面时停在最后一帧。</li>
 * <li>关机、没电（含 CPU 坏了）、页面清掉、换了操作者（换成另一个人；操作者走开、没人操作时不算）时清掉。</li>
 * </ul>
 * 各方法返回“这一帧变了没有”，变了由调用方发给附近的人。
 */
public final class ComputerScreen
{
    private final ScreenVote vote = new ScreenVote();
    /** 现在显示的（调色板下标）；没有为 null */
    @Nullable
    private byte[] frame;
    /** 上一个操作者（换成别人时清屏） */
    @Nullable
    private UUID lastOperator;

    @Nullable
    public byte[] frame()
    {
        return frame == null ? null : frame.clone();
    }

    public boolean hasPendingVotes()
    {
        return !vote.isEmpty();
    }

    /**
     * 收下一张（已经查过格式、权限）。不是当前页面 / 滚动位置的丢掉。
     *
     * @return 显示的那一帧变了
     */
    public boolean submit(UUID player, int page, int scroll, byte[] pixels, int currentPage, int currentScroll, Collection<UUID> viewers, @Nullable UUID operator, long now)
    {
        if(page != currentPage || scroll != currentScroll || currentPage < 0)
            return false;
        vote.submit(player, page, scroll, pixels, now);
        return decide(currentPage, currentScroll, viewers, operator, now);
    }

    /** 定时调（等别人的票、丢掉过时的票）；返回显示的那一帧变了 */
    public boolean decide(int currentPage, int currentScroll, Collection<UUID> viewers, @Nullable UUID operator, long now)
    {
        if(vote.isEmpty())
            return false;
        byte[] r = vote.decide(currentPage, currentScroll, viewers, operator, now);
        if(r == null || Arrays.equals(r, frame))
            return false;
        frame = r;
        return true;
    }

    /** 换了页面（新的上传编号、滚动回到顶上）：以前的票都过时了；画面先留着，等新的票 */
    public void pageChanged()
    {
        vote.clear();
    }

    /** 操作者变了：换成另一个人时清屏；没人操作（走开、下线）时留着 */
    public boolean operatorChanged(@Nullable UUID operator)
    {
        if(operator == null)
            return false;
        boolean other = lastOperator != null && !lastOperator.equals(operator);
        lastOperator = operator;
        return other && clear();
    }

    /** 关机、没电、页面清掉：清屏 */
    public boolean clear()
    {
        vote.clear();
        if(frame == null)
            return false;
        frame = null;
        return true;
    }
}
