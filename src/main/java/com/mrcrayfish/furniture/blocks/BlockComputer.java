package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.computer.ComputerCookies;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.mrcrayfish.furniture.computer.ComputerCpu;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

public class BlockComputer extends BlockFurnitureTile
{
    private static final AxisAlignedBB BOUNDING_BOX = new AxisAlignedBB(0.1, 0.0, 0.1, 0.9, 1.0, 0.9);

    public BlockComputer(Material material)
    {
        super(material);
        this.setHardness(1.0F);
        this.setSoundType(SoundType.ANVIL);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        if(placer instanceof EntityPlayer)
        {
            Triggers.trigger(Triggers.PLACE_APPLIANCE, (EntityPlayer) placer);
        }
        super.onBlockPlacedBy(world, pos, state, placer, stack);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        // 悠然服务器魔改：谁都能打开；第一个打开（且有使用权）的人操作，其他人旁观（见 TileEntityComputer）
        TileEntity tile_entity = worldIn.getTileEntity(pos);
        if(tile_entity instanceof TileEntityComputer && !worldIn.isRemote)
        {
            playerIn.openGui(MrCrayfishFurnitureMod.instance, 0, worldIn, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state)
    {
        // 悠然服务器魔改：电脑拆了（含被搬走），这里存的 Cookie 跟着删掉（服主 11-15）
        if(!world.isRemote)
        {
            ComputerCookies.remove(world, pos);
            // 外设槽里的定位模块掉出来
            TileEntity te = world.getTileEntity(pos);
            if(te instanceof TileEntityComputer)
                ((TileEntityComputer) te).dropPeripherals();
        }
        super.breakBlock(world, pos, state);
    }

    /** 掉落的电脑带着主板的数据（服主 11-16：芯片品质跟着电脑走）；外设、能量单元卡照旧另外掉出来 */
    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune)
    {
        super.getDrops(drops, world, pos, state, fortune);
        TileEntity te = world.getTileEntity(pos);
        // CPU 的运行数据（旋钮设定、温度、出事记录；服主 11-17）也跟着走
        if(te instanceof TileEntityComputer && (((TileEntityComputer) te).getBoard() != null || !((TileEntityComputer) te).getCpu().isEmpty()))
        {
            TileEntityComputer c = (TileEntityComputer) te;
            for(ItemStack drop : drops)
            {
                if(drop.getItem() == Item.getItemFromBlock(this))
                {
                    NBTTagCompound bet = new NBTTagCompound();
                    if(c.getBoard() != null)
                        bet.setTag(ComputerCpu.TAG_BOARD, c.getBoard().copy());
                    if(!c.getCpu().isEmpty())
                        bet.setTag(com.mrcrayfish.furniture.computer.CpuRuntime.TAG, c.getCpu().write());
                    drop.setTagInfo("BlockEntityTag", bet);
                }
            }
        }
    }

    /** 玩家挖时先掉东西再拆方块（不然掉落时方块实体已经没了，主板数据丢了） */
    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest)
    {
        return willHarvest || super.removedByPlayer(state, world, pos, player, willHarvest);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, BlockPos pos, IBlockState state, @Nullable TileEntity te, ItemStack stack)
    {
        super.harvestBlock(world, player, pos, state, te, stack);
        world.setBlockToAir(pos);
    }

    /** 物品提示：CPU 型号与频率（服主 11-16） */
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag)
    {
        super.addInformation(stack, world, tooltip, flag);
        NBTTagCompound bet = stack.getSubCompound("BlockEntityTag");
        tooltip.add(cpuLine(ComputerCpu.board(bet)));
    }

    /** “CPU：YR-2000，2.00 GHz” 这一行（物品提示、电脑界面共用） */
    @SideOnly(Side.CLIENT)
    public static String cpuLine(@Nullable NBTTagCompound board)
    {
        return cpuLine(board, false);
    }

    /** {@code brief}：不写“被最大频率封顶”那半句（电脑界面里地方小，那半句放在指着这一行时的提示里） */
    @SideOnly(Side.CLIENT)
    public static String cpuLine(@Nullable NBTTagCompound board, boolean brief)
    {
        return cpuLine(board, brief, 0);
    }

    /** {@code setting}：调频旋钮的设定（kHz；0 = 标准） */
    @SideOnly(Side.CLIENT)
    public static String cpuLine(@Nullable NBTTagCompound board, boolean brief, int setting)
    {
        String model = net.minecraft.client.resources.I18n.format("youranmod.chip.model." + ComputerCpu.modelId(board));
        if(ComputerCpu.dead(board))
            return net.minecraft.client.resources.I18n.format("cfm.computer.cpu.dead", model);
        String freq = ComputerCpu.formatFreq(ComputerCpu.clockKHz(board, setting));
        if(ComputerCpu.capped(board) && !brief)
            return net.minecraft.client.resources.I18n.format("cfm.computer.cpu.capped", model, freq,
                    ComputerCpu.formatFreq(moe.qingu.youran.api.pcb.BoardNbt.cpuStandardKHz(board)));
        return net.minecraft.client.resources.I18n.format("cfm.computer.cpu", model, freq);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        return BOUNDING_BOX;
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean isActualState)
    {
        addCollisionBoxToList(pos, entityBox, collidingBoxes, state.getCollisionBoundingBox(worldIn, pos));
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityComputer();
    }
}
