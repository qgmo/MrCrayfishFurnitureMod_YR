package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.gui.GuiTVClientConfig;
import com.mrcrayfish.furniture.items.ItemTVRemote;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageTVRemote;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.RemoteAction;
import com.mrcrayfish.furniture.tv.RemoteInput;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 拿着遥控器时截住“攻击 / 破坏”“使用物品 / 放置方块”“选取方块”三个键（悠然服务器魔改，按法见 {@link RemoteInput}）。
 * 按键位设置走，不写死鼠标左、右、中键：绑在鼠标上的在 {@link MouseEvent} 里截（取消后游戏收不到），
 * 绑在键盘上的在按键事件里把这次按下吃掉。大屏界面里由界面自己转给 {@link #handleButton}。
 */
public final class RemoteControls
{
    public static final RemoteControls INSTANCE = new RemoteControls();

    private final RemoteInput<BlockPos> input = new RemoteInput<>();
    private final List<RemoteInput.Output<BlockPos>> outputs = new ArrayList<>();

    private RemoteControls() {}

    /** 主手拿着遥控器（在世界里只认主手，免得副手拿着遥控器时左右键都被截走） */
    public static boolean holdingRemote()
    {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return player != null && player.getHeldItemMainhand().getItem() instanceof ItemTVRemote;
    }

    /** 主手或副手拿着遥控器（大屏界面里用：界面里点不了别的东西） */
    public static boolean holdingRemoteEitherHand()
    {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return player != null && (player.getHeldItemMainhand().getItem() instanceof ItemTVRemote || player.getHeldItemOffhand().getItem() instanceof ItemTVRemote);
    }

    /** 对着 16 格内的哪台电视（同服务端的判断） */
    @Nullable
    public static BlockPos aimedTv()
    {
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.player == null || mc.world == null)
            return null;
        TileEntityTV tv = ItemTVRemote.findTelevision(mc.world, mc.player);
        return tv == null ? null : tv.getPos();
    }

    /** 潜行键现在是不是按着（界面打开时游戏的按键状态被清了，直接读键盘 / 鼠标） */
    public static boolean sneakHeld()
    {
        KeyBinding sneak = Minecraft.getMinecraft().gameSettings.keyBindSneak;
        int code = sneak.getKeyCode();
        if(code == 0)
            return false;
        return code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code);
    }

    @Nullable
    private static RemoteInput.Button buttonFor(int keyCode)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if(keyCode == 0)
            return null;
        if(keyCode == mc.gameSettings.keyBindAttack.getKeyCode())
            return RemoteInput.Button.LEFT;
        if(keyCode == mc.gameSettings.keyBindUseItem.getKeyCode())
            return RemoteInput.Button.RIGHT;
        if(keyCode == mc.gameSettings.keyBindPickBlock.getKeyCode())
            return RemoteInput.Button.MIDDLE;
        return null;
    }

    /**
     * 一个键的按下 / 松开（keyCode 用 KeyBinding 的写法：鼠标键是 按键号 − 100）。
     * tv 是对着的电视（大屏里就是大屏那台）。返回 true 表示归遥控器（要拦住）。
     */
    public boolean handleButton(int keyCode, boolean down, @Nullable BlockPos tv, boolean sneaking)
    {
        return handleButton(keyCode, down, tv, sneaking, false);
    }

    /** eitherHand：大屏界面里副手的遥控器也算 */
    public boolean handleButton(int keyCode, boolean down, @Nullable BlockPos tv, boolean sneaking, boolean eitherHand)
    {
        RemoteInput.Button button = buttonFor(keyCode);
        if(button == null)
            return false;
        long now = System.currentTimeMillis();
        if(down)
            return (eitherHand ? holdingRemoteEitherHand() : holdingRemote()) && input.press(button, sneaking, tv, now, outputs);
        return input.release(button, now, outputs);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMouse(MouseEvent event)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if(event.getButton() < 0 || mc.currentScreen != null || mc.player == null)
            return;
        boolean down = event.isButtonstate();
        if(down && !holdingRemote())
            return;
        if(handleButton(event.getButton() - 100, down, down ? aimedTv() : null, mc.gameSettings.keyBindSneak.isKeyDown()))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.currentScreen != null || mc.player == null)
            return;
        int key = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter() + 256 : Keyboard.getEventKey();
        boolean down = Keyboard.getEventKeyState();
        if(Keyboard.isRepeatEvent())
            return;
        if(down && !holdingRemote())
            return;
        if(handleButton(key, down, down ? aimedTv() : null, mc.gameSettings.keyBindSneak.isKeyDown()))
        {
            // 绑在键盘上：把这次按下吃掉，游戏的处理（在这之后）看不到
            for(KeyBinding binding : new KeyBinding[]{mc.gameSettings.keyBindAttack, mc.gameSettings.keyBindUseItem, mc.gameSettings.keyBindPickBlock})
            {
                if(binding.getKeyCode() == key)
                {
                    while(binding.isPressed())
                    {
                        // 清掉按下次数
                    }
                    KeyBinding.setKeyBindState(key, false);
                }
            }
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event)
    {
        if(event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.player == null || !holdingRemote())
        {
            // 换了手里的东西、退出世界：放掉按着的键，不再出操作
            input.cancelAll();
            outputs.clear();
            return;
        }
        input.tick(System.currentTimeMillis(), outputs);
        flush();
    }

    /** 把攒下的操作发出去（大屏界面里每帧也调） */
    public void flush()
    {
        if(outputs.isEmpty())
            return;
        Minecraft mc = Minecraft.getMinecraft();
        List<RemoteInput.Output<BlockPos>> copy = new ArrayList<>(outputs);
        outputs.clear();
        for(RemoteInput.Output<BlockPos> out : copy)
        {
            if(out.isOpenConfig())
            {
                if(mc.currentScreen == null)
                    mc.displayGuiScreen(new GuiTVClientConfig(null));
            }
            else if(out.target != null && out.action != null && isLiveOnlyNoop(mc, out.target, out.action))
            {
                // 直播没有进度（服主 11-18）：暂停、快进、后退不发给服务端（也不耗电池），操作栏提示一下
                if(mc.player != null)
                    mc.player.sendStatusMessage(new TextComponentTranslation("cfm.tv.live.no_seek"), true);
            }
            else if(out.target != null && out.action != null)
            {
                // 带上这台电视的视频时长（后退时服务端没采信时长就按它夹到片尾以内）
                VideoSession session = TvVideoManager.INSTANCE.session(out.target);
                long duration = session == null ? 0 : session.getDurationMs();
                PacketHandler.INSTANCE.sendToServer(new MessageTVRemote(out.target, out.action, duration));
            }
        }
    }

    /** 对着的电视在放直播，按的是暂停、快进或后退 */
    private static boolean isLiveOnlyNoop(Minecraft mc, BlockPos target, RemoteAction action)
    {
        if(action != RemoteAction.TOGGLE_PLAY && action != RemoteAction.SEEK_BACK && action != RemoteAction.SEEK_FORWARD)
            return false;
        if(mc.world == null)
            return false;
        TileEntity te = mc.world.getTileEntity(target);
        return te instanceof TileEntityTV && ((TileEntityTV) te).isPowered() && ((TileEntityTV) te).isLiveChannel();
    }

    /** 大屏界面每帧：长按的步数 */
    public void tickInGui()
    {
        if(!holdingRemoteEitherHand())
        {
            input.cancelAll();
            outputs.clear();
            return;
        }
        input.tick(System.currentTimeMillis(), outputs);
        flush();
    }

    /** 打开、关掉界面时：放掉按着的键 */
    public void cancel()
    {
        input.cancelAll();
    }
}
