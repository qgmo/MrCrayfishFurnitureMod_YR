package com.mrcrayfish.furniture.gui;

import com.mrcrayfish.furniture.client.tv.ChannelDrafts;
import com.mrcrayfish.furniture.gui.containers.ContainerTVSettings;
import com.mrcrayfish.furniture.network.PacketHandler;
import com.mrcrayfish.furniture.network.message.MessageTVChannels;
import com.mrcrayfish.furniture.network.message.MessageTVChannelsResult;
import com.mrcrayfish.furniture.tileentity.TileEntityTV;
import com.mrcrayfish.furniture.tv.TvChannel;
import com.mrcrayfish.furniture.util.TextLimits;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * 电视的频道设置界面（悠然服务器魔改，只从大屏右下角的“设置”按钮进）：三个频道（GIF 网址或 BV / av 号，可混用）、
 * 拉伸开关、能量单元卡槽与电量条。
 * <p>
 * 填的内容随改随存成草稿（{@link ChannelDrafts}）：关界面、断线、服务器拒绝时都保留，重开时接着填；
 * 只有“保存”成功（服务端回了成功）或玩家点“放弃修改”并再点一次确认时才清掉。
 */
public class GuiTVSettings extends GuiContainer
{
    private static final ResourceLocation CHEST = new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int FIELD_X = 20, FIELD_Y = 18, FIELD_STEP = 18, FIELD_W = 148;
    private static final int INVENTORY_TOP = ContainerTVSettings.INVENTORY_Y - 14;
    /** 服务端多久没回应就允许再点保存 */
    private static final long REPLY_TIMEOUT_MS = 10_000L;

    private static int nextRequestId = 1;
    private static GuiTVSettings open;

    private final TileEntityTV tv;
    private final ContainerTVSettings container;
    private final String draftKey;
    private final GuiTextField[] fields = new GuiTextField[MessageTVChannels.CHANNELS];
    private boolean stretch;
    private GuiButton stretchButton;
    private GuiButton saveButton;
    private GuiButton discardButton;
    private long discardArmedAt;
    private int pendingRequest;
    private long pendingSince;
    private String status = "";
    private int statusColor = 0x404040;
    private int badField = -1;
    /** 初始化时从草稿或电视读出的值（initGui 会被反复调用，只读一次） */
    private ChannelDrafts.Draft initial;
    /** 最近一次保存成功的内容 */
    private ChannelDrafts.Draft saved;

    public GuiTVSettings(InventoryPlayer playerInventory, TileEntityTV tv)
    {
        super(new ContainerTVSettings(playerInventory, tv));
        this.container = (ContainerTVSettings) this.inventorySlots;
        this.tv = tv;
        this.xSize = 176;
        this.ySize = INVENTORY_TOP + 96;
        this.draftKey = ChannelDrafts.key(tv.getWorld().provider.getDimension(), tv.getPos());
        ChannelDrafts.Draft draft = ChannelDrafts.get(draftKey);
        if(draft == null)
        {
            draft = new ChannelDrafts.Draft();
            List<String> channels = tv.getChannels();
            for(int i = 0; i < draft.channels.length; i++)
                draft.channels[i] = i < channels.size() ? channels.get(i) : "";
            draft.stretch = tv.isStretched();
        }
        else
        {
            status = I18n.format("cfm.tv.settings.draft");
            statusColor = 0x705000;
        }
        this.initial = draft;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        ChannelDrafts.Draft values = currentDraft();
        for(int i = 0; i < fields.length; i++)
        {
            GuiTextField f = new GuiTextField(i, fontRenderer, guiLeft + FIELD_X, guiTop + FIELD_Y + i * FIELD_STEP, FIELD_W, 14);
            f.setMaxStringLength(TextLimits.MAX_URL_LENGTH);
            f.setText(values.channels[i] == null ? "" : values.channels[i]);
            f.setCursorPositionZero();
            fields[i] = f;
        }
        stretch = values.stretch;
        initial = null;
        buttonList.clear();
        stretchButton = new GuiButton(0, guiLeft + 8, guiTop + 72, 74, 16, "");
        discardButton = new GuiButton(1, guiLeft + 86, guiTop + 72, 40, 16, I18n.format("cfm.tv.settings.discard"));
        saveButton = new GuiButton(2, guiLeft + 130, guiTop + 72, 38, 16, I18n.format("cfm.tv.settings.save"));
        buttonList.add(stretchButton);
        buttonList.add(discardButton);
        buttonList.add(saveButton);
        updateButtons();
        open = this;
    }

    /** 现在界面上的内容（initGui 之前用初始值） */
    private ChannelDrafts.Draft currentDraft()
    {
        if(initial != null)
            return initial.copy();
        ChannelDrafts.Draft d = new ChannelDrafts.Draft();
        for(int i = 0; i < fields.length; i++)
            d.channels[i] = fields[i] == null ? "" : fields[i].getText();
        d.stretch = stretch;
        return d;
    }

    /** 和电视现在的设置不一样才算草稿；一样就不留 */
    private void storeDraft()
    {
        ChannelDrafts.Draft d = currentDraft();
        List<String> channels = tv.getChannels();
        boolean same = d.stretch == tv.isStretched();
        int n = 0;
        for(int i = 0; i < d.channels.length && same; i++)
        {
            String c = d.channels[i] == null ? "" : d.channels[i].trim();
            if(c.isEmpty())
                continue;
            same = n < channels.size() && channels.get(n).equals(c);
            n++;
        }
        same &= n == channels.size();
        // 刚保存成功、电视的同步还没到时，和保存的内容一样也不算草稿
        if(saved != null && java.util.Arrays.equals(saved.channels, trimmed(d.channels)) && saved.stretch == d.stretch)
            same = true;
        if(same)
            ChannelDrafts.remove(draftKey);
        else
            ChannelDrafts.put(draftKey, d);
    }

    private static String[] trimmed(String[] channels)
    {
        String[] out = new String[channels.length];
        for(int i = 0; i < channels.length; i++)
            out[i] = channels[i] == null ? "" : channels[i].trim();
        return out;
    }

    private void updateButtons()
    {
        stretchButton.displayString = I18n.format(stretch ? "cfm.tv.settings.stretch_on" : "cfm.tv.settings.stretch_off");
        boolean waiting = pendingRequest != 0 && System.currentTimeMillis() - pendingSince < REPLY_TIMEOUT_MS;
        saveButton.enabled = !waiting && !tv.isInvalid();
        boolean armed = System.currentTimeMillis() - discardArmedAt < 3000;
        discardButton.displayString = I18n.format(armed ? "cfm.tv.settings.discard_confirm" : "cfm.tv.settings.discard");
    }

    @Override
    public void updateScreen()
    {
        super.updateScreen();
        for(GuiTextField f : fields)
            f.updateCursorCounter();
        if(pendingRequest != 0 && System.currentTimeMillis() - pendingSince >= REPLY_TIMEOUT_MS)
        {
            pendingRequest = 0;
            setStatus(I18n.format("cfm.tv.settings.no_reply"), 0xA00000);
        }
        updateButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException
    {
        if(button == stretchButton)
        {
            stretch = !stretch;
            storeDraft();
        }
        else if(button == discardButton)
        {
            if(System.currentTimeMillis() - discardArmedAt < 3000)
            {
                // 玩家点了两次：放弃修改，回到电视现在的设置
                discardArmedAt = 0;
                ChannelDrafts.remove(draftKey);
                List<String> channels = tv.getChannels();
                for(int i = 0; i < fields.length; i++)
                    fields[i].setText(i < channels.size() ? channels.get(i) : "");
                stretch = tv.isStretched();
                badField = -1;
                setStatus(I18n.format("cfm.tv.settings.discarded"), 0x404040);
            }
            else
            {
                discardArmedAt = System.currentTimeMillis();
            }
        }
        else if(button == saveButton)
        {
            save();
        }
        updateButtons();
    }

    private void save()
    {
        storeDraft();
        String[] channels = new String[fields.length];
        for(int i = 0; i < fields.length; i++)
        {
            channels[i] = fields[i].getText().trim();
            String reason = TvChannel.rejectReason(channels[i]);
            if(reason != null)
            {
                badField = i;
                setStatus(I18n.format("cfm.tv.settings.bad_channel", i + 1, I18n.format(reason)), 0xA00000);
                return;
            }
        }
        badField = -1;
        pendingRequest = nextRequestId++;
        pendingSince = System.currentTimeMillis();
        PacketHandler.INSTANCE.sendToServer(new MessageTVChannels(tv.getPos(), pendingRequest, channels, stretch));
        setStatus(I18n.format("cfm.tv.settings.saving"), 0x404040);
    }

    /** 收到服务端的结果（客户端主线程） */
    public static void onResult(MessageTVChannelsResult message)
    {
        GuiTVSettings gui = open;
        if(gui == null || gui.mc.currentScreen != gui)
            return;
        gui.handleResult(message);
    }

    private void handleResult(MessageTVChannelsResult message)
    {
        if(message.getRequestId() != pendingRequest || !message.getPos().equals(tv.getPos()))
            return;
        pendingRequest = 0;
        String reason = message.getReason();
        if(reason == null)
        {
            // 成功：草稿清掉
            ChannelDrafts.remove(draftKey);
            saved = currentDraft();
            saved.channels = trimmed(saved.channels);
            badField = -1;
            setStatus(I18n.format("cfm.tv.settings.saved"), 0x007000);
        }
        else
        {
            badField = message.getIndex();
            String text = I18n.format(reason);
            setStatus(message.getIndex() >= 0 ? I18n.format("cfm.tv.settings.bad_channel", message.getIndex() + 1, text) : text, 0xA00000);
        }
        updateButtons();
    }

    private void setStatus(String text, int color)
    {
        status = text;
        statusColor = color;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException
    {
        if(keyCode == Keyboard.KEY_ESCAPE)
        {
            // 关界面：草稿留着；回到大屏
            storeDraft();
            BlockPos pos = tv.getPos();
            this.mc.player.closeScreen();
            this.mc.displayGuiScreen(new GuiTVScreen(pos));
            return;
        }
        for(GuiTextField f : fields)
        {
            if(f.isFocused())
            {
                if(keyCode == Keyboard.KEY_TAB)
                {
                    focusNext(f);
                }
                else if(keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
                {
                    save();
                }
                else if(f.textboxKeyTyped(typedChar, keyCode))
                {
                    storeDraft();
                }
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void focusNext(GuiTextField current)
    {
        for(int i = 0; i < fields.length; i++)
        {
            if(fields[i] == current)
            {
                current.setFocused(false);
                fields[(i + 1) % fields.length].setFocused(true);
                return;
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException
    {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for(GuiTextField f : fields)
            f.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void onGuiClosed()
    {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        // 不论怎么关的（断线、服务器关了界面、Esc），填的内容都留在草稿里
        storeDraft();
        if(open == this)
            open = null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        GlStateManager.disableLighting();
        for(int i = 0; i < fields.length; i++)
        {
            fields[i].drawTextBox();
            if(i == badField)
            {
                int x = guiLeft + FIELD_X - 1, y = guiTop + FIELD_Y + i * FIELD_STEP - 1;
                drawRect(x, y, x + FIELD_W + 2, y + 1, 0xFFFF4040);
                drawRect(x, y + 15, x + FIELD_W + 2, y + 16, 0xFFFF4040);
            }
        }
        this.renderHoveredToolTip(mouseX, mouseY);
        List<String> power = ApplianceGui.tooltip(guiLeft, guiTop, ContainerTVSettings.CARD_X, ContainerTVSettings.CARD_Y, container.getCardSlot(), tv.getAppliance(), mouseX, mouseY);
        if(power != null)
            drawHoveringText(power, mouseX, mouseY);
        for(int i = 0; i < fields.length; i++)
        {
            if(fields[i].getText().isEmpty() && !fields[i].isFocused())
            {
                fontRenderer.drawString(I18n.format("cfm.tv.settings.hint"), guiLeft + FIELD_X + 4, guiTop + FIELD_Y + i * FIELD_STEP + 3, 0x808080);
            }
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)
    {
        fontRenderer.drawString(I18n.format("cfm.tv.settings.title"), 8, 6, 0x404040);
        for(int i = 0; i < fields.length; i++)
            fontRenderer.drawString(Integer.toString(i + 1), 9, FIELD_Y + i * FIELD_STEP + 3, 0x404040);
        int textX = ContainerTVSettings.CARD_X + 30;
        List<String> lines = fontRenderer.listFormattedStringToWidth(status, xSize - textX - 6);
        for(int i = 0; i < Math.min(3, lines.size()); i++)
            fontRenderer.drawString(lines.get(i), textX, ContainerTVSettings.CARD_Y - 1 + i * 9, statusColor);
        fontRenderer.drawString(I18n.format("container.inventory"), 8, INVENTORY_TOP + 2, 0x404040);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)
    {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.getTextureManager().bindTexture(CHEST);
        int x = guiLeft, y = guiTop;
        this.drawTexturedModalRect(x, y, 0, 0, xSize, 17);
        // 中间没有格子：用贴图第 4 行（只有边框和底色）拉高
        drawScaledCustomSizeModalRect(x, y + 17, 0, 4, xSize, 1, xSize, INVENTORY_TOP - 17, 256, 256);
        this.drawTexturedModalRect(x, y + INVENTORY_TOP, 0, 126, xSize, 96);
        ApplianceGui.draw(guiLeft, guiTop, ContainerTVSettings.CARD_X, ContainerTVSettings.CARD_Y, container.getCardSlot(), tv.getAppliance());
    }
}
