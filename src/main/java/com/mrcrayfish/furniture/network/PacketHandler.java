package com.mrcrayfish.furniture.network;

import com.mrcrayfish.furniture.Reference;
import com.mrcrayfish.furniture.network.message.*;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class PacketHandler
{
    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MOD_ID);

    private static int messageId = 0;
    
    public static void init()
    {
        registerMessage(MessageConfig.class, MessageConfig.class, Side.CLIENT);
        registerMessage(MessageEmptyBin.class, MessageEmptyBin.class, Side.SERVER);
        registerMessage(MessageFart.class, MessageFart.class, Side.SERVER);
        registerMessage(MessageFillBath.class, MessageFillBath.class, Side.CLIENT);
        // 电脑（悠然服务器魔改）：原来 MineBay 的三个包换成浏览器的四个
        registerMessage(MessageComputerAction.class, MessageComputerAction.class, Side.SERVER);
        registerMessage(MessageComputerPage.class, MessageComputerPage.class, Side.SERVER);
        registerMessage(MessageComputerScroll.class, MessageComputerScroll.class, Side.SERVER);
        registerMessage(MessageComputerView.class, MessageComputerView.class, Side.CLIENT);
        registerMessage(MessageComputerTyping.class, MessageComputerTyping.class, Side.SERVER);
        registerMessage(MessageComputerHistory.class, MessageComputerHistory.class, Side.SERVER);
        registerMessage(MessageMicrowave.class, MessageMicrowave.class, Side.SERVER);
        registerMessage(MessageWashingMachine.class, MessageWashingMachine.class, Side.SERVER);
        registerMessage(MessageDishwasher.class, MessageDishwasher.class, Side.SERVER);
        registerMessage(MessageFreezer.class, MessageFreezer.class, Side.SERVER);
        registerMessage(MessageUpdateFields.class, MessageUpdateFields.class, Side.CLIENT);
        registerMessage(MessageDoorMat.class, MessageDoorMat.class, Side.SERVER);
        registerMessage(MessageSealCrate.class, MessageSealCrate.class, Side.SERVER);
        registerMessage(MessageUpdateValueContainer.class, MessageUpdateValueContainer.class, Side.SERVER);
        registerMessage(MessageSignItem.class, MessageSignItem.class, Side.SERVER);
        // 电视（悠然服务器魔改）
        registerMessage(MessageTVRemote.class, MessageTVRemote.class, Side.SERVER);
        registerMessage(MessageTVOpenSettings.class, MessageTVOpenSettings.class, Side.SERVER);
        registerMessage(MessageTVChannels.class, MessageTVChannels.class, Side.SERVER);
        registerMessage(MessageTVChannelsResult.class, MessageTVChannelsResult.class, Side.CLIENT);
        registerMessage(MessageTVDuration.class, MessageTVDuration.class, Side.SERVER);
    }
    
    private static <REQ extends IMessage, REPLY extends IMessage> void registerMessage(Class<? extends IMessageHandler<REQ, REPLY>> messageHandler, Class<REQ> requestMessageType, Side side)
    {
        INSTANCE.registerMessage(messageHandler, requestMessageType, messageId++, side);
    }
}
