package com.mrcrayfish.furniture.network.message;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 → 客户端：家具电脑的 CPU 现状（频率、温度、热量、出错率……，{@code computer.CpuRuntime#status}），每秒发给开着这台电脑界面的人，
 * 拧了旋钮、出了事时马上发（悠然服务器魔改，服主 11-17）。
 */
public class MessageComputerCpuState implements IMessage, IMessageHandler<MessageComputerCpuState, IMessage>
{
    private BlockPos pos = BlockPos.ORIGIN;
    private NBTTagCompound status = new NBTTagCompound();

    public MessageComputerCpuState() {}

    public MessageComputerCpuState(BlockPos pos, NBTTagCompound status)
    {
        this.pos = pos;
        this.status = status;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.pos = BlockPos.fromLong(buf.readLong());
        NBTTagCompound t = ByteBufUtils.readTag(buf);
        this.status = t == null ? new NBTTagCompound() : t;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeTag(buf, status);
    }

    @Override
    public IMessage onMessage(MessageComputerCpuState message, MessageContext ctx)
    {
        Minecraft.getMinecraft().addScheduledTask(() ->
        {
            Minecraft mc = Minecraft.getMinecraft();
            TileEntity te = mc.world == null || !mc.world.isBlockLoaded(message.pos) ? null : mc.world.getTileEntity(message.pos);
            if(te instanceof com.mrcrayfish.furniture.tileentity.TileEntityComputer)
                ((com.mrcrayfish.furniture.tileentity.TileEntityComputer) te).setCpuStatus(message.status);
        });
        return null;
    }
}
