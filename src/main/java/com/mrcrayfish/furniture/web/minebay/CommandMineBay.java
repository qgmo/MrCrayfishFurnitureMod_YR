package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 管理员看 MineBay 在线客服转来的留言（OP，权限等级 2）：
 * <ul>
 * <li>{@code /minebay tickets}：没处理的留言（新的在前，最多 20 条）；{@code /minebay tickets all}：最近 20 条（含处理过的）；</li>
 * <li>{@code /minebay ticket <编号>}：一条的全文；</li>
 * <li>{@code /minebay close <编号>}：标成处理过（玩家那边看不到，只是管理员自己记）。</li>
 * </ul>
 * 新留言进来时在线的 OP 聊天栏里有提示；OP 上线时有没处理的留言也提示一句。
 */
public class CommandMineBay extends CommandBase
{
    static final int LIST = 20;

    @Override
    public String getName()
    {
        return "minebay";
    }

    @Override
    public String getUsage(ICommandSender sender)
    {
        return "/minebay tickets [all] | /minebay ticket <编号> | /minebay close <编号>";
    }

    @Override
    public int getRequiredPermissionLevel()
    {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
    {
        SupportData d = SupportData.get(server);
        if(d == null || args.length == 0)
            throw new WrongUsageException(getUsage(sender));
        switch(args[0])
        {
            case "tickets":
            {
                boolean all = args.length > 1 && args[1].equals("all");
                List<SupportData.Ticket> list = new ArrayList<>(d.tickets());
                Collections.reverse(list);
                int shown = 0;
                send(sender, TextFormatting.GOLD, all ? "MineBay 客服留言（最近 " + LIST + " 条）：" : "MineBay 没处理的客服留言（共 " + d.openTickets() + " 条）：");
                for(SupportData.Ticket t : list)
                {
                    if(shown >= LIST)
                        break;
                    if(!all && t.closed())
                        continue;
                    send(sender, t.closed() ? TextFormatting.GRAY : TextFormatting.WHITE, line(t, 40));
                    shown++;
                }
                if(shown == 0)
                    send(sender, TextFormatting.GRAY, "（没有）");
                return;
            }
            case "ticket":
            {
                SupportData.Ticket t = d.ticket(id(args));
                if(t == null)
                    throw new CommandException("没有这条留言");
                send(sender, TextFormatting.GOLD, "#" + t.id + " " + MineBayPages.TIME.format(Instant.ofEpochMilli(t.time)) + " " + t.playerName + "（" + t.player + "）"
                        + (t.closed() ? "，" + t.closedBy() + " 已处理" : "，没处理"));
                send(sender, TextFormatting.WHITE, t.text);
                return;
            }
            case "close":
            {
                int id = id(args);
                if(!d.closeTicket(id, sender.getName()))
                    throw new CommandException("没有这条留言，或者已经处理过了");
                send(sender, TextFormatting.GREEN, "留言 #" + id + " 标成已处理。");
                return;
            }
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    static String line(SupportData.Ticket t, int max)
    {
        return "#" + t.id + " " + MineBayPages.TIME.format(Instant.ofEpochMilli(t.time)) + " " + t.playerName + (t.closed() ? "（已处理）" : "") + "："
                + SupportText.clip(t.text, max);
    }

    private static int id(String[] args) throws CommandException
    {
        if(args.length < 2)
            throw new CommandException("要写留言编号");
        return parseInt(args[1].startsWith("#") ? args[1].substring(1) : args[1], 1);
    }

    private static void send(ICommandSender sender, TextFormatting color, String s)
    {
        TextComponentString c = new TextComponentString(s);
        c.getStyle().setColor(color);
        sender.sendMessage(c);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos)
    {
        if(args.length == 1)
            return getListOfStringsMatchingLastWord(args, Arrays.asList("tickets", "ticket", "close"));
        if(args.length == 2 && args[0].equals("tickets"))
            return getListOfStringsMatchingLastWord(args, Collections.singletonList("all"));
        return Collections.emptyList();
    }
}
