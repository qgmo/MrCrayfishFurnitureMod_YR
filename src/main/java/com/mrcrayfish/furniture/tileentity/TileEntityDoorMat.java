package com.mrcrayfish.furniture.tileentity;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

public class TileEntityDoorMat extends TileEntitySyncClient
{
    private String message = null;
    private int colour = 0;

    /**
     * 可以写这块门垫文字的玩家：放下门垫的人，写一次后清空（悠然服务器魔改）。
     * 门垫的文字界面只在放下时由客户端打开，服务端没有对应的容器，这里照原版告示牌的做法
     * 记下放置者。只在内存里，不存档。
     */
    @Nullable
    private UUID editor = null;

    public void setEditor(@Nullable UUID editor)
    {
        this.editor = editor;
    }

    public boolean isEditor(UUID player)
    {
        return player.equals(this.editor);
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public void setColour(int colour)
    {
        this.colour = 15 - colour;
    }

    public int getColour()
    {
        return colour;
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        this.message = tagCompound.getString("message");
        this.colour = tagCompound.getInteger("colour");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        if(this.message != null)
        {
            tagCompound.setString("message", this.message);
        }
        tagCompound.setInteger("colour", colour);
        return tagCompound;
    }
}
