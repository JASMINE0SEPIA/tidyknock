package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

// 这一部分负责找出某个位置附近的所有箱子和木桶
public final class ChestFinder {

	// 搜索半径以被敲的箱子为中心, 上下左右前后各 12 格
	public static final int RADIUS = 12;

	private ChestFinder() {
	}

	public static List<Storage> findNearby(Level level, Player player, BlockPos center) {
		List<Storage> found = new ArrayList<>();
		// 大箱子的两半都会被扫到, 处理完一半就把另一半记在这里, 免得算两次
		Set<BlockPos> seen = new HashSet<>();

		BlockPos min = center.offset(-RADIUS, -RADIUS, -RADIUS);
		BlockPos max = center.offset(RADIUS, RADIUS, RADIUS);

		// 把这个立方体里的每一个格子都看一遍
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			// 没加载的区块不碰
			if (!level.isLoaded(pos) || seen.contains(pos)) {
				continue;
			}

			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof ChestBlockEntity) && !(blockEntity instanceof BarrelBlockEntity)) {
				continue;
			}

			// betweenClosed 为了省内存, 一直在改同一个 pos 对象
			// 要存下来必须用 immutable() 复制一份定格的坐标
			BlockPos here = pos.immutable();
			List<BlockPos> blocks = new ArrayList<>();
			blocks.add(here);
			Container container;

			BlockState state = level.getBlockState(here);
			if (state.getBlock() instanceof ChestBlock chestBlock) {
				// 大箱子: 把另一半也算进来, 并标记为已处理
				if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
					BlockPos other = here.relative(ChestBlock.getConnectedDirection(state));
					blocks.add(other);
					seen.add(other);
				}
				// 原版拿箱子格子的方法, 大箱子会自动拼成 54 格
				// 最后一个参数 false 表示 "被方块压住或有猫坐着就打不开", 这时返回 null
				container = ChestBlock.getContainer(chestBlock, state, level, here, false);
			} else {
				container = (BarrelBlockEntity) blockEntity;
			}

			if (container == null || !canTouch(level, player, blocks)) {
				continue;
			}

			found.add(new Storage(blocks, container));
		}

		return found;
	}

	// 这个玩家能不能动这个箱子, 大箱子的每一半都要过关
	private static boolean canTouch(Level level, Player player, List<BlockPos> blocks) {
		for (BlockPos pos : blocks) {
			// 出生点保护区, 世界边界外这类地方, 原版本来就不让玩家碰
			if (!level.mayInteract(player, pos)) {
				return false;
			}

			BlockEntity blockEntity = level.getBlockEntity(pos);

			// 上了锁的箱子, 玩家主手没拿着钥匙就不碰 (和原版开箱的规则一样)
			if (blockEntity instanceof BaseContainerBlockEntity box && !box.canOpen(player)) {
				return false;
			}

			// 还没生成战利品的箱子 (比如没人开过的地牢箱) 不算
			// 只要读一下它的格子, 游戏就会当场生成战利品, 相当于替你开了
			if (blockEntity instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) {
				return false;
			}
		}
		return true;
	}
}
