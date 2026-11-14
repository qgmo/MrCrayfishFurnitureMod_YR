package com.mrcrayfish.furniture.computer;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * 网页里物品图标的写法（{@code data-yr-item}、{@code data-yr-item-name}）：{@code 注册名[@数据值][{SNBT}]}。
 * 带的 NBT 只用来画对图标、显示对名字与说明（药水的颜色与效果、附魔光效、刷怪蛋的生物、烟花），所以两边都只留几个键：
 * {@value #MAX_NBT} 字以内，键只认 {@link #KEYS}，{@code EntityTag} 只留 {@code id}。不留会联网（如头颅的皮肤）、
 * 会显示任意文字（如改名、Lore）的键。
 */
public final class ItemIcons
{
    public static final int MAX_NBT = 512;
    static final String[] KEYS = {"Potion", "CustomPotionColor", "CustomPotionEffects", "StoredEnchantments", "ench", "Fireworks", "Explosion", "EntityTag"};

    private ItemIcons() {}

    /** 只留白名单里的键；没有可留的返回 null */
    @Nullable
    public static NBTTagCompound filter(@Nullable NBTTagCompound tag)
    {
        if(tag == null)
            return null;
        NBTTagCompound out = new NBTTagCompound();
        for(String k : KEYS)
        {
            if(!tag.hasKey(k))
                continue;
            if(k.equals("EntityTag"))
            {
                NBTTagCompound e = tag.getCompoundTag("EntityTag");
                if(e.hasKey("id", Constants.NBT.TAG_STRING))
                {
                    NBTTagCompound id = new NBTTagCompound();
                    id.setString("id", e.getString("id"));
                    out.setTag(k, id);
                }
                continue;
            }
            NBTBase v = tag.getTag(k);
            if(v != null)
                out.setTag(k, v.copy());
        }
        return out.isEmpty() ? null : out;
    }

    /** 物品写法后面接的 NBT 部分（过滤后；没有或太长为空串） */
    public static String nbtSuffix(@Nullable NBTTagCompound tag)
    {
        NBTTagCompound f = filter(tag);
        if(f == null)
            return "";
        String s = f.toString();
        return s.length() > MAX_NBT ? "" : s;
    }
}
