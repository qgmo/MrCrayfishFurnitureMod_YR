package com.mrcrayfish.furniture.client.tv;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 频道设置界面的草稿（悠然服务器魔改）。填了一半的内容在关界面、断线、服务器拒绝时都留着，
 * 只有保存成功、或玩家自己点“放弃修改”（再点一次确认）才清掉。存在内存里，同时写进 {@code cfm-tv/drafts.json}，
 * 游戏重启后也还在。按“服务器地址 + 维度 + 坐标”区分电视。
 */
public final class ChannelDrafts
{
    private static final int MAX_DRAFTS = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, Draft>>() {}.getType();

    public static final class Draft
    {
        public String[] channels = new String[3];
        public boolean stretch;

        public Draft copy()
        {
            Draft d = new Draft();
            d.channels = channels.clone();
            d.stretch = stretch;
            return d;
        }
    }

    private static Map<String, Draft> drafts;

    private ChannelDrafts() {}

    public static String key(int dimension, BlockPos pos)
    {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc.getCurrentServerData();
        String where = server != null ? server.serverIP : mc.isSingleplayer() && mc.getIntegratedServer() != null ? "local:" + mc.getIntegratedServer().getFolderName() : "unknown";
        return where + "|" + dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Nullable
    public static synchronized Draft get(String key)
    {
        load();
        Draft d = drafts.get(key);
        return d == null ? null : d.copy();
    }

    public static synchronized void put(String key, Draft draft)
    {
        load();
        drafts.remove(key);
        drafts.put(key, draft.copy());
        while(drafts.size() > MAX_DRAFTS)
            drafts.remove(drafts.keySet().iterator().next());
        save();
    }

    public static synchronized void remove(String key)
    {
        load();
        if(drafts.remove(key) != null)
            save();
    }

    private static File file()
    {
        return new File(TvFiles.root(), "drafts.json");
    }

    private static void load()
    {
        if(drafts != null)
            return;
        drafts = new LinkedHashMap<>();
        File f = file();
        if(!f.isFile())
            return;
        try(Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
        {
            Map<String, Draft> loaded = GSON.fromJson(r, TYPE);
            if(loaded != null)
            {
                for(Map.Entry<String, Draft> e : loaded.entrySet())
                {
                    Draft d = e.getValue();
                    if(d != null && d.channels != null && d.channels.length == 3)
                        drafts.put(e.getKey(), d);
                }
            }
        }
        catch(IOException | RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().warn("TV channel drafts unreadable, starting empty: {}", e.toString());
        }
    }

    private static void save()
    {
        File f = file();
        File tmp = new File(f.getPath() + ".tmp");
        try(Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
        {
            GSON.toJson(drafts, TYPE, w);
        }
        catch(IOException e)
        {
            MrCrayfishFurnitureMod.logger().warn("Could not save TV channel drafts: {}", e.toString());
            return;
        }
        try
        {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch(IOException e)
        {
            MrCrayfishFurnitureMod.logger().warn("Could not save TV channel drafts: {}", e.toString());
        }
    }
}
