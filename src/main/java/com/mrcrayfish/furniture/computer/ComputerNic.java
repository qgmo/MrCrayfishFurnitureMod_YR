package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.ClientRelayNic;

/**
 * 家具电脑的网卡（悠然服务器魔改，服主 11-27）：YouranMod 的 {@link ClientRelayNic}，协议栈跑在操作者的客户端上，服务器只当路由器。
 * 电脑方块加载时插到它那一格（{@code YouranNetworkApi.plug}，地址是这一格的 0 号地址）、卸载或拆掉时拔掉；有电、有人操作时接到操作者的客户端。
 * 网卡不带通电、操作者、能力：电脑没电（CPU 坏了、死机了）时这里不收包（发给它的包路由器回“目标不可达”），也不接客户端，所以也不发。
 * 网站要知道是谁、在哪，用两种外设：身份模块（操作者证明）、定位模块（位置证明），都由电脑这边拿着这张网卡替操作者签（{@link ComputerProofs}）。
 */
public final class ComputerNic extends ClientRelayNic
{
    /** 电脑有电、在运行（主线程写，网络线程读） */
    private volatile boolean running;

    public void setRunning(boolean running)
    {
        this.running = running;
    }

    public boolean isRunning()
    {
        return running;
    }

    /** 网络线程：没电时收不了 */
    @Override
    public boolean receive(byte[] packet)
    {
        return running && super.receive(packet);
    }
}
