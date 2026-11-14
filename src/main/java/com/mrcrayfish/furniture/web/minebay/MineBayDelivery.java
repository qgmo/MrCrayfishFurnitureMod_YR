package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 模组商品的发货回调（{@link MineBayProduct.Builder#delivery}）：发货时现做物品，例如物品 NBT 里要写买家、编号等。
 * 都在服务端主线程上调用。
 * <p>
 * 结算是全有或全无的：先对这一单里的每件商品调 {@link #prepare}，全部准备好、钱也够，才扣钱、发货，最后调 {@link #delivered}。
 * 所以 {@link #prepare} 不能改游戏状态（不要在这里登记、扣东西）——它返回之后这一单仍可能因为别的商品准备不了而取消。
 */
public interface MineBayDelivery
{
    /**
     * 为买家准备 {@code quantity} 份要发的物品（扣钱之前调用）。返回的物品由 MineBay 放进买家背包，背包满了掉在脚边；
     * 超过最大堆叠数的会被拆开。
     *
     * @return 要发的物品；返回 null、空表或抛异常时整单取消，不扣钱
     */
    @Nullable
    List<ItemStack> prepare(EntityPlayerMP buyer, int quantity);

    /** 扣了钱、物品已经发出以后调用（可选；例如记账、登记软盘编号）。这里抛的异常只记日志，不影响这一单。 */
    default void delivered(EntityPlayerMP buyer, int quantity, List<ItemStack> stacks)
    {
    }
}
