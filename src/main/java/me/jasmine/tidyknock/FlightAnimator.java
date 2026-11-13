package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// 这一部分负责归位表演: 按节奏一发一发地真实搬运, 每发配一个飞行动画
// 每一发起飞的瞬间物品就已经进箱子了, 飞行本身是纯视觉效果
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

	// 随机散开: 每一发都偏一点点, 一把东西飞出去像个扇面
	// 起点上下左右前后最多偏多少格, 像从手里一把抓出去
	private static final double START_SPREAD = 0.3;
	// 抛的高度最多高低多少 (0.2 = 20%)
	private static final double ARC_SPREAD = 0.2;
	// 路线向左右两侧最多偏多少格
	private static final double SIDE_SPREAD = 0.4;

	// 落进箱子时 "啵" 一声的音量, 原版捡东西是 0.2, 连发时声音多, 稍微再小一点
	private static final float LANDING_VOLUME = 0.15f;

	// 所有发依次起飞的时间窗口, 不含最后一发的飞行时间
	// 42 + 18 = 60 tick, 所以整场表演最长 3 秒
	private static final double LAUNCH_WINDOW_TICKS = 42;
	// 但间隔最短 0.25 tick (1 tick 能发 4 个), 最长 2 tick
	private static final double MIN_INTERVAL = 0.25;
	private static final double MAX_INTERVAL = 2;

	// 自己数的 tick, 用来安排起飞时间
	private static int now = 0;
	// 每个玩家正在进行的归位表演, 一个玩家同时只有一场
	private static final Map<UUID, Show> shows = new HashMap<>();
	// 正在飞的物品模型
	private static final List<Flying> flying = new ArrayList<>();

	// 一场归位表演: 按顺序一发一发地真实搬运, 并配上飞行动画
	private static final class Show {
		ServerLevel level;
		Player player;
		List<ItemSorter.Shot> shots;
		int startTick;
		double interval;
		// 下一发是第几发
		int next;
		// 最后一件物品落地的时间, 到点才显示结果
		int endTick;
		// 这一场里已经弹过盖子的箱子
		Set<Storage> opened = new HashSet<>();
		// 真的搬成功了的物品种类, 结束时显示在动作栏
		Set<Item> moved = new HashSet<>();

		int launchTickOf(int index) {
			return startTick + (int) (index * interval);
		}
	}

	// 飞行中的一件物品, age 每 tick 都要变, 所以用普通的类而不是 record
	private static final class Flying {
		Display.ItemDisplay entity;
		ServerLevel level;
		// 飞向哪个箱子, 落地放音效时用来判断 "这个箱子这一刻是不是已经响过了"
		Storage target;
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

	// 这个玩家是不是正在表演中
	public static boolean isPlaying(Player player) {
		return shows.containsKey(player.getUUID());
	}

	// 开始一场归位表演
	public static void start(ServerLevel level, Player player, List<ItemSorter.Shot> shots) {
		if (shots.isEmpty()) {
			return;
		}

		Show show = new Show();
		show.level = level;
		show.player = player;
		show.shots = shots;
		show.startTick = now + 1;
		show.endTick = now;
		show.interval = Math.clamp(LAUNCH_WINDOW_TICKS / shots.size(), MIN_INTERVAL, MAX_INTERVAL);
		shows.put(player.getUUID(), show);
	}

	private static void tick() {
		now++;

		// 每场表演里到点的发起飞
		Iterator<Show> running = shows.values().iterator();
		while (running.hasNext()) {
			Show show = running.next();
			// 玩家下线或者换了维度, 就不演了
			// 没起飞的发还没真正搬过, 物品都还好好地在背包里, 直接取消就行
			if (show.player.isRemoved() || show.player.level() != show.level) {
				running.remove();
				continue;
			}
			while (show.next < show.shots.size() && show.launchTickOf(show.next) <= now) {
				fire(show, show.shots.get(show.next));
				show.next++;
			}
			// 最后一发也起飞了, 还要等它飞到箱子, 再显示结果
			if (show.next >= show.shots.size() && now >= show.endTick) {
				KnockHandler.showSummary(show.player, show.moved);
				running.remove();
			}
		}

		// 正在飞的往前挪一步, 飞完的删掉
		// 这一 tick 已经 "啵" 过的箱子, 同一个箱子同一刻落进好几发也只响一声
		Set<Storage> sounded = new HashSet<>();
		Iterator<Flying> air = flying.iterator();
		while (air.hasNext()) {
			Flying item = air.next();
			item.age++;
			if (item.age >= FLIGHT_TICKS) {
				if (sounded.add(item.target)) {
					playLandingSound(item);
				}
				item.entity.discard();
				air.remove();
			} else {
				item.entity.setPos(positionAt(item, (double) item.age / FLIGHT_TICKS));
			}
		}
	}

	// 执行一发: 先真实地搬, 搬成功了才配上动画, 保证看到的都是真的
	private static void fire(Show show, ItemSorter.Shot shot) {
		int moved = ItemSorter.transfer(show.level, show.player, shot);
		if (moved == 0) {
			return;
		}
		show.moved.add(shot.item());
		// 临时: 每个箱子在一场表演里只弹一次盖子, 之后会改成跟着飞行时间开关
		if (show.opened.add(shot.target())) {
			shot.target().container().startOpen(show.player);
		}
		flying.add(launch(show.level, show.player, shot));
		// 最后一件飞完的时间, 用来决定什么时候显示结果
		show.endTick = Math.max(show.endTick, now + FLIGHT_TICKS);
	}

	// 在玩家眼前生成一个物品模型, 并算好它的路线
	private static Flying launch(ServerLevel level, Player player, ItemSorter.Shot shot) {
		Flying item = new Flying();
		item.level = level;
		item.target = shot.target();
		List<BlockPos> blocks = shot.target().blocks();
		RandomSource random = level.getRandom();

		// 起点: 玩家眼睛正前方, 稍微偏下, 保证一出现就在视野里, 像是从手里抛出去
		// 再随机偏一点点, 不是每一发都从同一个点射出来
		item.start = player.getEyePosition().add(player.getLookAngle().scale(START_AHEAD)).add(0, -0.4, 0)
				.add(jitter(random, START_SPREAD), jitter(random, START_SPREAD), jitter(random, START_SPREAD));

		// 终点: 箱子开口那一面的正中心, 大箱子取两格的中点
		// 终点不随机, 保证每一发最后都准确落进开口
		Vec3 outward = openingOf(level, blocks.get(0)).getUnitVec3();
		item.end = centerOf(blocks).add(outward.scale(0.5));
		// 抬手高度: 近的轻轻一抛, 远的抛高一点, 再随机高低一点
		double arc = Math.clamp(item.start.distanceTo(item.end) * ARC_RATIO, MIN_ARC, MAX_ARC)
				* (1 + jitter(random, ARC_SPREAD));
		// 控制点 1: 起点正上方, 决定物品 "往上抛出去" 的方向和力度
		// 再往路线的左边或右边偏一点, 整条曲线就会向那一侧鼓出去
		item.control1 = item.start.add(0, arc, 0)
				.add(sideways(item.start, item.end).scale(jitter(random, SIDE_SPREAD)));
		// 控制点 2: 开口外侧, 决定物品最后 "顺着开口落进去" 的方向和力度
		item.control2 = item.end.add(outward.scale(arc));

		// 展示实体: 原版用来在空中摆放物品模型的实体, 摸不到也捡不起
		Display.ItemDisplay entity = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		entity.setItemStack(shot.icon().copy());
		// 按掉落物的样子显示, 大小正合适
		entity.setItemTransform(ItemDisplayContext.GROUND);
		// 服务端每次挪动它, 客户端用 1 tick 平滑地滑过去, 而不是一格一格地跳
		entity.setPosRotInterpolationDuration(1);
		entity.setPos(item.start);
		level.addFreshEntity(entity);

		item.entity = entity;
		return item;
	}

	// 物品落进箱子时 "啵" 一声, 用的是原版捡东西的音效
	private static void playLandingSound(Flying item) {
		RandomSource random = item.level.getRandom();
		// 音高照抄原版捡东西的算法: 每次随机高一点或低一点, 连发时 "啵啵啵" 高低错落, 不机械
		float pitch = ((random.nextFloat() - random.nextFloat()) * 0.7f + 1.0f) * 2.0f;
		// 第一个参数 null 表示 "不是某个玩家自己发出的声音", 所以周围所有玩家都能听到
		item.level.playSound(null, item.end.x, item.end.y, item.end.z,
				SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, LANDING_VOLUME, pitch);
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

	// 在 -amount 到 +amount 之间随机取数
	// triangle 取到 0 附近的机会大, 取到两头的机会小, 所以理论上大多只偏一点点, 偶尔偏得多🤔
	private static double jitter(RandomSource random, double amount) {
		return random.triangle(0.0, amount);
	// 理论是对的
	}

	// 和路线垂直, 水平指向侧面的方向, 长度为 1
	private static Vec3 sideways(Vec3 from, Vec3 to) {
		// 只看水平方向: 把 (x, z) 转 90 度就是 (-z, x)
		Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
		if (flat.lengthSqr() < 1.0E-4) {
			// 箱子就在正下方或正上方, 没有 "侧面" 可言, 随便取一个水平方向
			return new Vec3(1, 0, 0);
		}
		return new Vec3(-flat.z, 0, flat.x).normalize();
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
