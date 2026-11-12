package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;

// 这一部分负责识别 "潜行 + 左键敲箱子", 然后把归位交给动画
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
				List<ChestRule> rules = new ArrayList<>();
				for (Storage storage : ChestFinder.findNearby(level, player, pos)) {
					rules.add(ChestRule.of(level, storage));
				}

				// 排优先级: 贴了展示框的排前面, 同一级里离玩家近的排前面
				BlockPos playerPos = player.blockPosition();
				rules.sort(Comparator
						.comparing((ChestRule rule) -> !rule.labeled())
						.thenComparingDouble(rule -> rule.storage().blocks().get(0).distSqr(playerPos)));

				// 先演算: 算出每一发搬几个, 去哪, 真实物品一个都不动
				List<ItemSorter.Shot> shots = ItemSorter.plan(player, rules);
				if (shots.isEmpty()) {
					player.sendOverlayMessage(Component.literal("不知道要放到哪里"));
				} else {
					// 交给动画: 每发起飞时才真正搬这一发
					FlightAnimator.start((ServerLevel) level, player, shots);
				}
			}

			// 返回 SUCCESS 表示 "左键已接管", 所以箱子不会开始被挖
			return InteractionResult.SUCCESS;
		});
	}

	// 一场表演结束时调用, 把结果显示在动作栏
	public static void showSummary(Player player, Set<Item> moved) {
		Set<Item> leftover = ItemSorter.leftoverKinds(player);
		player.sendOverlayMessage(summary(moved, leftover));
	}

	// 显示在动作栏 (快捷栏上方) 的一行字
	private static Component summary(Set<Item> moved, Set<Item> leftover) {
		if (moved.isEmpty()) {
			return Component.literal("不知道要放到哪里");
		}
		String text = "放进去了 " + moved.size() + " 种东西";
		if (!leftover.isEmpty()) {
			text += ", " + leftover.size() + " 种无处可去";
		}
		return Component.literal(text);
	}
}
