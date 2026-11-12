package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// 这一部分负责归位的飞行动画
// 纯视觉效果: 动画开始时物品早就已经放进箱子了
public final class FlightAnimator {

	// 每件物品从起飞到钻进箱子用多少 tick (20 tick = 1 秒)
	private static final int FLIGHT_TICKS = 18;
	// 起点在玩家眼睛前方多远
	private static final double START_AHEAD = 0.8;
	// 抛的力度: 抬手高度 = 距离 x 这个比例, 再限制在最小和最大值之间
	// 比例要大于 0.5 才有 "快-慢-快" 的感觉, 越大越明显
	private static final double ARC_RATIO = 0.7;
	private static final double MIN_ARC = 1.0;
	private static final double MAX_ARC = 5.0;

	// 所有物品依次起飞的总时长预算, 种类越多, 间隔越短
	private static final int LAUNCH_BUDGET_TICKS = 30;
	// 但间隔最短 1 tick, 最长 4 tick
	private static final int MIN_INTERVAL = 1;
	private static final int MAX_INTERVAL = 4;

	// 自己数的 tick, 用来安排起飞时间
	private static int now = 0;
	// 排队等起飞的
	private static final List<Waiting> waiting = new ArrayList<>();
	// 正在飞的
	private static final List<Flying> flying = new ArrayList<>();

	private record Waiting(ServerLevel level, Player player, ItemSorter.Flight flight, int launchAt) {
	}

	// 飞行中的一件物品, age 每 tick 都要变, 所以用普通的类而不是 record
	private static final class Flying {
		Display.ItemDisplay entity;
		// 一条三次贝塞尔曲线的四个点: 起点, 两个控制点, 终点
		Vec3 start;
		Vec3 control1;
		Vec3 control2;
		Vec3 end;
		int age;
	}

	private FlightAnimator() {
	}

	// 在模组初始化时调用一次, 让 tick() 每个游戏刻都执行
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> tick());
	}

	// 把一次归位的飞行清单排进队列, 一件一件隔开起飞
	public static void play(ServerLevel level, Player player, List<ItemSorter.Flight> flights) {
		if (flights.isEmpty()) {
			return;
		}
		int interval = Math.clamp(LAUNCH_BUDGET_TICKS / flights.size(), MIN_INTERVAL, MAX_INTERVAL);
		for (int i = 0; i < flights.size(); i++) {
			waiting.add(new Waiting(level, player, flights.get(i), now + i * interval));
		}
	}

	private static void tick() {
		now++;

		// 到点的物品起飞
		Iterator<Waiting> queue = waiting.iterator();
		while (queue.hasNext()) {
			Waiting next = queue.next();
			if (next.launchAt() <= now) {
				flying.add(launch(next));
				queue.remove();
			}
		}

		// 正在飞的往前挪一步, 飞完的删掉
		Iterator<Flying> air = flying.iterator();
		while (air.hasNext()) {
			Flying item = air.next();
			item.age++;
			if (item.age >= FLIGHT_TICKS) {
				item.entity.discard();
				air.remove();
			} else {
				item.entity.setPos(positionAt(item, (double) item.age / FLIGHT_TICKS));
			}
		}
	}

	// 在玩家身边生成一个物品模型, 并算好它的航线
	private static Flying launch(Waiting next) {
		Flying item = new Flying();
		Player player = next.player();
		List<BlockPos> blocks = next.flight().target().blocks();

		// 起点: 玩家眼睛正前方, 稍微偏下, 保证一出现就在视野里, 像是从手里抛出去
		item.start = player.getEyePosition().add(player.getLookAngle().scale(START_AHEAD)).add(0, -0.4, 0);

		// 终点: 箱子开口那一面的正中心, 大箱子取两格的中点
		Vec3 outward = openingOf(next.level(), blocks.get(0)).getUnitVec3();
		item.end = centerOf(blocks).add(outward.scale(0.5));
		// 抬手高度: 近的轻轻一抛, 远的抛高一点
		double arc = Math.clamp(item.start.distanceTo(item.end) * ARC_RATIO, MIN_ARC, MAX_ARC);
		// 控制点 1: 起点正上方, 决定物品 "往上抛出去" 的方向和力度
		item.control1 = item.start.add(0, arc, 0);
		// 控制点 2: 开口外侧, 决定物品最后 "顺着开口落进去" 的方向和力度
		item.control2 = item.end.add(outward.scale(arc));

		// 展示实体: 原版用来在空中摆放物品模型的实体, 摸不到也捡不起
		Display.ItemDisplay entity = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, next.level());
		entity.setItemStack(next.flight().icon().copy());
		// 按掉落物的样子显示, 大小正合适
		entity.setItemTransform(ItemDisplayContext.GROUND);
		// 服务端每次挪动它, 客户端用 1 tick 平滑地滑过去, 而不是一格一格地跳
		entity.setPosRotInterpolationDuration(1);
		entity.setPos(item.start);
		next.level().addFreshEntity(entity);

		item.entity = entity;
		return item;
	}

	// t 从 0 到 1, 算出这一刻物品在三次贝塞尔曲线上的位置
	// 不需要缓动函数: 控制点离得越远, 那一头就飞得越快
	// 两个控制点都 "拉" 得够远, 中间自然就慢下来, 形成 "快-慢-快" 的抛物感
	private static Vec3 positionAt(Flying item, double t) {
		// 一层层 lerp 往下缩: 4 个点 -> 3 个点 -> 2 个点 -> 1 个点
		Vec3 a = item.start.lerp(item.control1, t);
		Vec3 b = item.control1.lerp(item.control2, t);
		Vec3 c = item.control2.lerp(item.end, t);
		Vec3 ab = a.lerp(b, t);
		Vec3 bc = b.lerp(c, t);
		return ab.lerp(bc, t);
	}

	// 开口朝哪: 木桶看它的朝向, 箱子永远朝上
	private static Direction openingOf(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof BarrelBlock) {
			return state.getValue(BarrelBlock.FACING);
		}
		return Direction.UP;
	}

	private static Vec3 centerOf(List<BlockPos> blocks) {
		Vec3 sum = Vec3.ZERO;
		for (BlockPos pos : blocks) {
			sum = sum.add(Vec3.atCenterOf(pos));
		}
		return sum.scale(1.0 / blocks.size());
	}
}
