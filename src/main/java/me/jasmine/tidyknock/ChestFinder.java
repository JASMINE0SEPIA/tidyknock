package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

// 这一部分负责找出某个位置附近的所有箱子和木桶
public final class ChestFinder {

	// 搜索半径以被敲的箱子为中心, 上下左右前后各 12 格
	public static final int RADIUS = 12;

	private ChestFinder() {
	}

	public static List<BlockPos> findNearby(Level level, BlockPos center) {
		List<BlockPos> found = new ArrayList<>();

		BlockPos min = center.offset(-RADIUS, -RADIUS, -RADIUS);
		BlockPos max = center.offset(RADIUS, RADIUS, RADIUS);

		// 把这个立方体里的每一个格子都看一遍
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			// 没加载的区块不碰
			if (!level.isLoaded(pos)) {
				continue;
			}

			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity) {
				// betweenClosed 为了省内存, 一直在改同一个 pos 对象
				// 要存下来必须用 immutable() 复制一份定格的坐标
				found.add(pos.immutable());
			}
		}

		return found;
	}
}
