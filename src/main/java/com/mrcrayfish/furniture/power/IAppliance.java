package com.mrcrayfish.furniture.power;

/**
 * 用电的家具（悠然服务器魔改）：方块实体带一份 {@link ApplianceEnergy}（缓存、能量单元卡、进电面）。
 */
public interface IAppliance
{
    ApplianceEnergy getAppliance();
}
