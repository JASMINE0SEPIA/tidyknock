package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;

// 这一部分负责识别"潜行 + 左键敲箱子"
public final class KnockHandler {

	private KnockHandler() {
	}

	// 在模组初始化时调用一次, 把监听器挂到左键方块事件
	public static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			// 旁观模式的玩家不算
			if (player.isSpectator()) {
				return InteractionResult.PASS;
			}

			// 必须是潜行状态
			if (!player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}

			// 敲的必须是箱子/陷阱箱/木桶
			Block block = level.getBlockState(pos).getBlock();
			if (!(block instanceof ChestBlock) && !(block instanceof BarrelBlock)) {
				return InteractionResult.PASS;
			}

			// 这里说明条件全部满足
			// 只在服务端做实际的事; 客户端那边直接返回 SUCCESS, 让它把这次敲击告诉服务端
			if (!level.isClientSide()) {
				player.sendSystemMessage(Component.literal("敲了敲了" + pos.toShortString())); // 敲了敲了好欸

				List<ChestRule> rules = new ArrayList<>();
				for (Storage storage : ChestFinder.findNearby(level, pos)) {
					rules.add(ChestRule.of(level, storage));
				}

				// 排优先级: 贴了展示框的排前面, 同一级里离玩家近的排前面
				BlockPos playerPos = player.blockPosition();
				rules.sort(Comparator
						.comparing((ChestRule rule) -> !rule.labeled())
						.thenComparingDouble(rule -> rule.storage().blocks().get(0).distSqr(playerPos)));

				int moved = ItemSorter.sortInto(player, rules);
				player.sendSystemMessage(Component.literal("归位了 " + moved + " 个物品"));
			}

			// 返回 SUCCESS 表示 "左键已接管", 所以箱子不会开始被挖
			return InteractionResult.SUCCESS;
		});
	}
}
