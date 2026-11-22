package com.mrcrayfish.furniture.power;

import com.mrcrayfish.furniture.gui.slots.PartSlot;
import com.mrcrayfish.furniture.handler.ConfigurationHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 家具“部件”的取放规则（悠然服务器魔改，服主 11-22）：在配置 {@code parts-creative-only-dimensions} 列出的维度（默认主城 3）里，
 * 家具里的部件——电器的能量单元卡、电脑的外设——只有创造模式的玩家能放、拿；生存、冒险、旁观都不行。
 * 打开界面、用电器、接管电脑、上网、看电视照常。
 * <p>
 * 只靠服务端判断：客户端（老版本）点了会先看到东西被拿起来，服务端拒绝后把界面和光标上的物品重新同步给玩家，物品不丢也不多。
 * 新版客户端里同样的判断只用来少发注定被拒的点击，不作数。
 * <p>
 * 判断本身（{@link #isRestricted}、{@link #canChange(boolean, int, int[])}、{@link #judge}）是纯函数；
 * 碰到 Minecraft 的部分（取玩家的维度和游戏模式、发提示、同步界面）都过 {@link Env}，单元测试里可以替换。
 */
public final class PartRules
{
    /** 同一个玩家两条提示之间至少隔这么多 tick（2 秒） */
    public static final int NOTICE_TICKS = 40;

    /** 提示用服务端发的中文原文：旧版客户端没有新语言键，发键名会显示成一串键。服务器就是中文服，所以直接发文字 */
    public static final String NOTICE = "主城的公共家具，部件只有创造模式能取放";

    /** 一次点击怎么处理 */
    public enum Verdict
    {
        /** 照常处理 */
        PASS,
        /** 拒绝这次点击 */
        DENY
    }

    /** 碰到 Minecraft 的部分；测试里替换 */
    public interface Env
    {
        int[] dimensions();

        int dimension(EntityPlayer player);

        boolean creative(EntityPlayer player);

        /** 服务端上才做同步、发提示 */
        boolean isServer(EntityPlayer player);

        /** 节流用的时间（tick） */
        long time(EntityPlayer player);

        /** 把界面和光标上的物品重新发给玩家（服务端） */
        void resync(EntityPlayer player, Container container);

        /** 给玩家一条提示（服务端） */
        void message(EntityPlayer player);
    }

    private static final class McEnv implements Env
    {
        @Override
        public int[] dimensions()
        {
            return ConfigurationHandler.partsCreativeOnlyDimensions;
        }

        @Override
        public int dimension(EntityPlayer player)
        {
            return player.world.provider.getDimension();
        }

        @Override
        public boolean creative(EntityPlayer player)
        {
            // 服务端以游戏模式管理器为准；客户端用同步来的能力
            if(player instanceof EntityPlayerMP)
                return ((EntityPlayerMP) player).interactionManager.isCreative();
            return player.capabilities.isCreativeMode;
        }

        @Override
        public boolean isServer(EntityPlayer player)
        {
            return player instanceof EntityPlayerMP;
        }

        @Override
        public long time(EntityPlayer player)
        {
            return player.world.getTotalWorldTime();
        }

        @Override
        public void resync(EntityPlayer player, Container container)
        {
            EntityPlayerMP mp = (EntityPlayerMP) player;
            mp.sendAllContents(container, container.getInventory());
            mp.connection.sendPacket(new SPacketSetSlot(-1, -1, mp.inventory.getItemStack()));
        }

        @Override
        public void message(EntityPlayer player)
        {
            ITextComponent text = new TextComponentString(NOTICE);
            player.sendStatusMessage(text, true);
        }
    }

    private static Env env = new McEnv();
    private static final Throttle THROTTLE = new Throttle(NOTICE_TICKS);

    private PartRules() {}

    /** 测试用：换掉与 Minecraft 打交道的部分；传 null 恢复；同时清空提示的节流记录 */
    public static void setEnv(Env newEnv)
    {
        env = newEnv != null ? newEnv : new McEnv();
        THROTTLE.clear();
    }

    // ---- 纯判断 ----

    /** 这个维度要不要限制 */
    public static boolean isRestricted(int dimension, int[] restrictedDimensions)
    {
        if(restrictedDimensions == null)
            return false;
        for(int d : restrictedDimensions)
        {
            if(d == dimension)
                return true;
        }
        return false;
    }

    /** 在这个维度、这种游戏模式能不能改部件：不在限制的维度都行，在的话只有创造 */
    public static boolean canChange(boolean creative, int dimension, int[] restrictedDimensions)
    {
        return creative || !isRestricted(dimension, restrictedDimensions);
    }

    /**
     * 一次界面点击要不要拒绝（玩家已经是受限的）。
     *
     * @param slots 界面上的全部格子
     */
    public static Verdict judge(ClickType type, int slotId, List<Slot> slots)
    {
        if(slotId < 0 || slotId >= slots.size())
            return Verdict.PASS;
        Slot clicked = slots.get(slotId);
        // 点部件格本身：放、拿、交换（数字键 / F）、丢弃、拖动分发、双击收集，全部不认
        if(clicked instanceof PartSlot)
            return Verdict.DENY;
        // Shift 点别处的物品：它要是能进某个空着的部件格就会被挪进去，不认
        if(type == ClickType.QUICK_MOVE && clicked.getHasStack())
        {
            ItemStack stack = clicked.getStack();
            for(Slot s : slots)
            {
                if(s instanceof PartSlot && !s.getHasStack() && s.isItemValid(stack))
                    return Verdict.DENY;
            }
        }
        return Verdict.PASS;
    }

    // ---- 要用到玩家的 ----

    /** 这个玩家现在能不能改部件 */
    public static boolean canChange(EntityPlayer player)
    {
        return canChange(env.creative(player), env.dimension(player), env.dimensions());
    }

    /** 点击的处理（Container#slotClick 的第一层） */
    public interface Click
    {
        ItemStack apply(int slotId, int dragType, ClickType type, EntityPlayer player);
    }

    /**
     * 各家具界面的 {@code slotClick} 统一走这里。受限玩家点部件格、或 Shift 点的东西会进部件格时拒绝并重新同步；
     * 拖动分发结束时（客户端自己已经把物品分进了部件格）也重新同步一次，服务端没分进去的以服务端为准。
     */
    public static ItemStack slotClick(Container container, int slotId, int dragType, ClickType type, EntityPlayer player, Click click)
    {
        if(canChange(player))
            return click.apply(slotId, dragType, type, player);
        if(judge(type, slotId, container.inventorySlots) == Verdict.DENY)
        {
            // 拖动分发里经过部件格：这一格不加进去，最后统一同步
            if(type != ClickType.QUICK_CRAFT)
                deny(container, player);
            else if(env.isServer(player))
                notice(player);
            return ItemStack.EMPTY;
        }
        ItemStack result = click.apply(slotId, dragType, type, player);
        if(type == ClickType.QUICK_CRAFT && (dragType & 3) == 2 && env.isServer(player) && hasPartSlot(container))
            env.resync(player, container);
        return result;
    }

    private static boolean hasPartSlot(Container container)
    {
        for(Slot s : container.inventorySlots)
        {
            if(s instanceof PartSlot)
                return true;
        }
        return false;
    }

    /** 拒绝：重新同步界面和光标，再提示（限流） */
    public static void deny(Container container, EntityPlayer player)
    {
        if(!env.isServer(player))
            return;
        env.resync(player, container);
        notice(player);
    }

    /** 拒绝了不经过界面的操作（潜行左键插拔卡）：只提示 */
    public static void notice(EntityPlayer player)
    {
        if(env.isServer(player) && THROTTLE.tryAcquire(player.getUniqueID(), env.time(player)))
            env.message(player);
    }

    /** 每个玩家的提示间隔：纯函数式，时间由调用方给 */
    public static final class Throttle
    {
        private final int interval;
        private final Map<UUID, Long> last = new HashMap<>();

        public Throttle(int interval)
        {
            this.interval = interval;
        }

        /** 现在（now）能不能给这个玩家发提示；能的话记下 */
        public synchronized boolean tryAcquire(UUID player, long now)
        {
            Long before = last.get(player);
            // 世界时间往回走（换了世界、重进）也放行
            if(before != null && now >= before && now - before < interval)
                return false;
            last.put(player, now);
            if(last.size() > 512)
                last.values().removeIf(t -> now < t || now - t >= interval);
            return true;
        }

        public synchronized void clear()
        {
            last.clear();
        }

        synchronized int size()
        {
            return last.size();
        }
    }
}
