package com.mrcrayfish.furniture.util;

import java.util.*;

/**
 * 从 RFTools 能量单元网络取电的成本（纯逻辑，悠然服务器魔改）。照 RFTools 1.12-7.73 的
 * {@code PowerCellNetwork.Network#updateCostFactor}（以整合包里的 jar 为准，javap 核对过）：
 * <ul>
 * <li>网络里相连（六面相邻、同一维度）的能量单元算一团；电器自己当单独一团；</li>
 * <li>对每个其他团：距离按 [最小距离, 封顶距离] 夹住，跨维度按封顶距离；
 * {@code factor = 1 + 距离/封顶距离 × (成本系数 − 1) × 该团格数/总格数}；</li>
 * <li>电器这一团的成本 = (1 + 各其他团 factor 之和) / 团数。</li>
 * </ul>
 * 团与团的距离 RFTools 取两团各自“集合里第一个”格子（HashSet 的顺序，近似任意一格）；这里取电器到那一团最近的格子，结果确定。
 * <p>
 * 取电照 7.73 的 {@code PowerCellTileEntity#sendOutEnergy}：每次最多给 {@code (int)(网络电量 / 成本)}，
 * 网络里扣的是实际给出的量（7.73 的 jar 里成本只限制“最多给多少”，不额外扣电）。
 */
public final class PowerCellCost
{
    /** 一个能量单元或电器的位置 */
    public static final class Cell
    {
        public final int x, y, z, dim;

        public Cell(int x, int y, int z, int dim)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
        }

        @Override
        public boolean equals(Object o)
        {
            if(!(o instanceof Cell))
                return false;
            Cell c = (Cell) o;
            return x == c.x && y == c.y && z == c.z && dim == c.dim;
        }

        @Override
        public int hashCode()
        {
            return ((x * 31 + y) * 31 + z) * 31 + dim;
        }
    }

    private PowerCellCost() {}

    /** 把网络里的格子分成团（六面相邻、同一维度的连在一起） */
    public static List<List<Cell>> blobs(Collection<Cell> cells)
    {
        Set<Cell> todo = new HashSet<>(cells);
        List<List<Cell>> result = new ArrayList<>();
        while(!todo.isEmpty())
        {
            Cell start = todo.iterator().next();
            todo.remove(start);
            List<Cell> blob = new ArrayList<>();
            Deque<Cell> queue = new ArrayDeque<>();
            queue.add(start);
            while(!queue.isEmpty())
            {
                Cell c = queue.poll();
                blob.add(c);
                Cell[] around = {
                        new Cell(c.x + 1, c.y, c.z, c.dim), new Cell(c.x - 1, c.y, c.z, c.dim),
                        new Cell(c.x, c.y + 1, c.z, c.dim), new Cell(c.x, c.y - 1, c.z, c.dim),
                        new Cell(c.x, c.y, c.z + 1, c.dim), new Cell(c.x, c.y, c.z - 1, c.dim)};
                for(Cell n : around)
                {
                    if(todo.remove(n))
                        queue.add(n);
                }
            }
            result.add(blob);
        }
        return result;
    }

    /** 电器到一团的距离：跨维度按封顶；同维度取最近一格，夹在 [minDistance, distanceCap] */
    static double distance(Cell appliance, List<Cell> blob, double distanceCap, double minDistance)
    {
        double best = Double.MAX_VALUE;
        for(Cell c : blob)
        {
            if(c.dim != appliance.dim)
                continue;
            double dx = c.x - appliance.x, dy = c.y - appliance.y, dz = c.z - appliance.z;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        if(best == Double.MAX_VALUE)
            return distanceCap;
        if(best > distanceCap)
            return distanceCap;
        if(best < minDistance)
            return minDistance;
        return best;
    }

    /**
     * 电器（单独一团）从网络取电的成本系数（≥ 1）。网络是空的时按 1。
     */
    public static float factor(Collection<Cell> network, Cell appliance, double costFactor, double distanceCap, double minDistance)
    {
        if(network.isEmpty() || distanceCap <= 0)
            return 1.0F;
        List<List<Cell>> blobs = blobs(network);
        int total = network.size() + 1;
        double sum = 1.0;
        for(List<Cell> blob : blobs)
        {
            double dist = distance(appliance, blob, distanceCap, minDistance);
            double part = (double) blob.size() / total;
            sum += 1 + (dist / distanceCap) * (costFactor - 1) * part;
        }
        return (float) (sum / (blobs.size() + 1));
    }

    /** 这一次最多能从网络取多少（RFTools：{@code (int)(电量 / 成本)}） */
    public static int available(int networkEnergy, float factor)
    {
        if(networkEnergy <= 0)
            return 0;
        if(!(factor > 0))
            return networkEnergy;
        return (int) (networkEnergy / factor);
    }
}
