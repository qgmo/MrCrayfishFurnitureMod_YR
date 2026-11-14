package com.mrcrayfish.furniture.network.message;

import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.api.RecipeData;
import com.mrcrayfish.furniture.api.Recipes;
import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.network.ServerThread;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import com.mrcrayfish.furniture.util.RejectLog;

public class MessageMineBayBuy implements IMessage, IMessageHandler<MessageMineBayBuy, IMessage>
{
    private int itemNum, x, y, z;

    public MessageMineBayBuy() {}

    public MessageMineBayBuy(int itemNum, int x, int y, int z)
    {
        this.itemNum = itemNum;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        this.itemNum = buf.readInt();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        buf.writeInt(itemNum);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    @Override
    public IMessage onMessage(MessageMineBayBuy message, MessageContext ctx)
    {
        ServerThread.run(ctx, player -> handle(message, player));
        return null;
    }

    private static void handle(MessageMineBayBuy message, EntityPlayerMP player)
    {
        World world = player.world;
        BlockPos pos = new BlockPos(message.x, message.y, message.z);
        if(!world.isAreaLoaded(pos, 0))
            return;

        Container container = player.openContainer;
        if(!(container instanceof ContainerComputer))
            return;

        TileEntity tileEntity = player.world.getTileEntity(new BlockPos(message.x, message.y, message.z));
        if(tileEntity instanceof TileEntityComputer)
        {
            TileEntityComputer tileEntityComputer = (TileEntityComputer) tileEntity;
            if(((ContainerComputer) container).getComputerInventory() != tileEntityComputer)
                return;
            if(!tileEntityComputer.getAppliance().isRunning())
            {
                // 没电时界面黑着、按钮点不了（悠然服务器魔改）
                RejectLog.log(player, "MineBay request while the computer has no power at " + tileEntityComputer.getPos());
                return;
            }

            ItemStack buySlot = tileEntityComputer.getStackInSlot(0);
            if(buySlot.isEmpty())
                return;

            RecipeData[] data = Recipes.getMineBayItems();
            if(message.itemNum < 0 || message.itemNum >= data.length)
                return;

            RecipeData recipe = data[message.itemNum];
            int price = recipe.getPrice();
            if(recipe.getCurrency().getItem() == buySlot.getItem() && buySlot.getCount() >= price)
            {
                tileEntityComputer.takeEmeraldFromSlot(price);
                EntityItem entityItem = new EntityItem(player.world, player.posX, player.posY + 1, player.posZ, data[message.itemNum].getInput().copy());
                player.world.spawnEntity(entityItem);
                Triggers.trigger(Triggers.MINEBAY_PURCHASE, player);
            }
        }
    }

}
