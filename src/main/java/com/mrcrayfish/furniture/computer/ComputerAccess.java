package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.gui.containers.ContainerComputer;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * 电脑相关的包在服务端主线程上的统一校验：区块已加载、方块实体是电脑、距离不超过 8 格、发包人开着这台电脑的界面；
 * 需要时再要求发包人是当前操作者、电脑有电。不合格就拒绝并记一条限频日志（{@link RejectLog}）。
 */
public final class ComputerAccess
{
    public static final double MAX_DISTANCE_SQ = 64.0;

    private ComputerAccess() {}

    @Nullable
    public static TileEntityComputer check(EntityPlayerMP player, BlockPos pos, boolean operator, boolean powered, String what)
    {
        if(!player.world.isBlockLoaded(pos))
        {
            RejectLog.log(player, what + ": chunk not loaded at " + pos);
            return null;
        }
        TileEntity te = player.world.getTileEntity(pos);
        if(!(te instanceof TileEntityComputer))
        {
            RejectLog.log(player, what + ": no computer at " + pos);
            return null;
        }
        TileEntityComputer computer = (TileEntityComputer) te;
        if(player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQ)
        {
            RejectLog.log(player, what + ": too far from computer at " + pos);
            return null;
        }
        if(!(player.openContainer instanceof ContainerComputer) || ((ContainerComputer) player.openContainer).getComputer() != computer)
        {
            RejectLog.log(player, what + ": computer screen not open at " + pos);
            return null;
        }
        if(operator && !computer.isOperator(player))
        {
            RejectLog.log(player, what + ": not the operator of computer at " + pos);
            return null;
        }
        if(powered && !computer.getAppliance().isRunning())
        {
            RejectLog.log(player, what + ": computer has no power at " + pos);
            return null;
        }
        return computer;
    }
}
