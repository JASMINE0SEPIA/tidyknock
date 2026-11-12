package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

// 这一部分负责把背包里的物品搬进箱子, 分两步:
// 1. plan: 敲下时只演算, 算出每一发搬几个, 去哪, 真实物品一个都不动
// 2. transfer: 每一发起飞时, 真实地搬这一发
// 这样任何时刻物品要么在背包, 要么在箱子, 不会悬在半空
public final class ItemSorter {

	// 一发大约带多少个物品
	private static final int ITEMS_PER_SHOT = 8;
	// 同一种物品飞向同一个箱子最多几发
	private static final int MAX_SHOTS_PER_ROUTE = 16;
	// 一次归位总共最多几发, 超出就按比例缩减
	private static final int MAX_SHOTS_TOTAL = 96;

	private ItemSorter() {
	}

	// 一发: 带着 amount 个 item 飞向 target, icon 是飞行时显示的模型
	public record Shot(Item item, ItemStack icon, Storage target, int amount) {
	}

	// 一条路线: 某种物品去某个箱子, 演算时累计一共能放进去多少个
	private static final class Route {
		final Item item;
		final ItemStack icon;
		final Storage target;
		int count;

		Route(Item item, ItemStack icon, Storage target) {
			this.item = item;
			this.icon = icon;
			this.target = target;
		}
	}

	// rules 要事先排好优先级, 越靠前的箱子越先被塞
	public static List<Shot> plan(Player player, List<ChestRule> rules) {
		Inventory inventory = player.getInventory();
		// 箱子的复制品, 演算都在复制品上做, 真实的箱子一格都不动
		Map<Storage, Container> copies = new IdentityHashMap<>();
		List<Route> routes = new ArrayList<>();

		// 处理 0~35 格: 0~8 是快捷栏, 9~35 是背包
		// 36 往后是盔甲和副手, 不动
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack real = inventory.getItem(slot);
			if (real.isEmpty() || isKept(inventory, slot, real)) {
				continue;
			}

			// 背包这一格的复制品
			ItemStack stack = real.copy();
			Item item = stack.getItem();
			for (ChestRule rule : rules) {
				if (stack.isEmpty()) {
					break;
				}
				if (!rule.items().contains(item)) {
					continue;
				}
				Container copy = copies.computeIfAbsent(rule.storage(), storage -> copyOf(storage.container()));
				int before = stack.getCount();
				insert(copy, stack);
				int moved = before - stack.getCount();
				if (moved > 0) {
					routeFor(routes, item, real, rule.storage()).count += moved;
				}
			}
		}

		return toShots(routes);
	}

	// 真实地搬一发: 现场从背包里找这种物品, 最多搬 shot.amount 个进箱子
	// 返回实际搬了多少, 0 表示这一发作废 (东西已经不在背包里, 或者箱子没了, 满了)
	public static int transfer(Level level, Player player, Shot shot) {
		if (!stillThere(level, shot.target())) {
			return 0;
		}
		Container container = shot.target().container();
		Inventory inventory = player.getInventory();
		int remaining = shot.amount();

		for (int slot = 0; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			// 保留规则现场重新判断: 演算之后玩家可能换了手上拿的格子
			if (stack.isEmpty() || stack.getItem() != shot.item() || isKept(inventory, slot, stack)) {
				continue;
			}
			// 从这一格切下一部分去塞箱子
			ItemStack portion = stack.split(remaining);
			int taken = portion.getCount();
			insert(container, portion);
			remaining -= taken - portion.getCount();

			// 箱子装不下的部分放回原来那一格, 箱子满了也就不用再试别的格子了
			if (!portion.isEmpty()) {
				if (stack.isEmpty()) {
					inventory.setItem(slot, portion);
				} else {
					stack.grow(portion.getCount());
				}
				break;
			}
		}

		inventory.setChanged();
		return shot.amount() - remaining;
	}

	// 背包里还剩哪些种类的物品没有归位 (保留的格子不算)
	public static Set<Item> leftoverKinds(Player player) {
		Inventory inventory = player.getInventory();
		Set<Item> kinds = new HashSet<>();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && !isKept(inventory, slot, stack)) {
				kinds.add(stack.getItem());
			}
		}
		return kinds;
	}

	// 把每条路线拆成若干发
	private static List<Shot> toShots(List<Route> routes) {
		// 先按每发 8 个算发数, 每条路线 1~16 发
		int[] counts = new int[routes.size()];
		int total = 0;
		for (int i = 0; i < routes.size(); i++) {
			counts[i] = Math.clamp(Math.ceilDiv(routes.get(i).count, ITEMS_PER_SHOT), 1, MAX_SHOTS_PER_ROUTE);
			total += counts[i];
		}
		// 超出总上限就按比例缩减, 但每条路线至少留 1 发
		if (total > MAX_SHOTS_TOTAL) {
			for (int i = 0; i < counts.length; i++) {
				counts[i] = Math.max(1, counts[i] * MAX_SHOTS_TOTAL / total);
			}

			// 取整和 "至少 1 发" 可能让总数还是超标, 就从发数最多的路线继续减
			// 如果路线本身就超过 96 条, 减到每条 1 发就停, 保证每种东西都还能飞出去
			int sum = 0;
			for (int count : counts) {
				sum += count;
			}
			while (sum > MAX_SHOTS_TOTAL) {
				int largest = -1;
				for (int i = 0; i < counts.length; i++) {
					if (counts[i] > 1 && (largest < 0 || counts[i] > counts[largest])) {
						largest = i;
					}
				}
				if (largest < 0) {
					break;
				}
				counts[largest]--;
				sum--;
			}
		}

		// 把数量尽量平均地分给每一发, 除不尽的余数从前几发里各多分 1 个
		// 同一条路线的几发挨在一起, 看起来就是 "一把圆石, 接着一把泥土"
		List<Shot> shots = new ArrayList<>();
		for (int i = 0; i < routes.size(); i++) {
			Route route = routes.get(i);
			int n = counts[i];
			for (int k = 0; k < n; k++) {
				int amount = route.count / n + (k < route.count % n ? 1 : 0);
				shots.add(new Shot(route.item, route.icon, route.target, amount));
			}
		}
		return shots;
	}

	// 找到 "这种物品 -> 这个箱子" 的路线, 没有就新建一条
	private static Route routeFor(List<Route> routes, Item item, ItemStack sample, Storage target) {
		for (Route route : routes) {
			if (route.item == item && route.target == target) {
				return route;
			}
		}
		// 飞行模型用 1 个的复制品, 附魔光效这类外观也会带上
		Route route = new Route(item, sample.copyWithCount(1), target);
		routes.add(route);
		return route;
	}

	// 复制一个箱子的所有格子, 演算用
	private static Container copyOf(Container container) {
		SimpleContainer copy = new SimpleContainer(container.getContainerSize());
		for (int i = 0; i < container.getContainerSize(); i++) {
			copy.setItem(i, container.getItem(i).copy());
		}
		return copy;
	}

	// 箱子还在不在: 演算之后到这一发之间, 箱子可能被挖掉, 或者大箱子被拆成了小箱子
	private static boolean stillThere(Level level, Storage storage) {
		Container whole = storage.container();
		for (BlockPos pos : storage.blocks()) {
			if (!level.isLoaded(pos)) {
				return false;
			}
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof Container part) || blockEntity.isRemoved()) {
				return false;
			}
			// 这一格必须还是当初那个箱子的一部分
			if (whole != part && !(whole instanceof CompoundContainer compound && compound.contains(part))) {
				return false;
			}
		}
		return true;
	}

	// 留在身上不归位的东西
	private static boolean isKept(Inventory inventory, int slot, ItemStack stack) {
		// 手上正拿着的那一格
		if (slot == inventory.getSelectedSlot()) {
			return true;
		}
		// 快捷栏里的工具: 这里把 "有耐久度的东西" 当作工具
		// 镐斧锹锄, 剑, 弓, 剪刀, 钓鱼竿, 打火石, 盾牌都算; 背包里的工具照常归位
		return Inventory.isHotbarSlot(slot) && stack.isDamageableItem();
	}

	// 尽量把 stack 塞进 container, 塞进去多少就从 stack 里扣掉多少
	private static void insert(Container container, ItemStack stack) {
		int before = stack.getCount();

		// 第一轮: 先往已有的同类物品上叠, 不浪费新格子
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack there = container.getItem(i);
			// 同种物品且附加数据也一样 (比如附魔, 改名) 才能叠在一起
			if (there.isEmpty() || !ItemStack.isSameItemSameComponents(there, stack)) {
				continue;
			}
			int room = container.getMaxStackSize(there) - there.getCount();
			int amount = Math.min(room, stack.getCount());
			if (amount > 0) {
				there.grow(amount);
				stack.shrink(amount);
			}
		}

		// 第二轮: 还有剩的, 再放进空格子
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) {
				// split 从 stack 里切下一部分, 切下来的放进箱子, 剩下的还在 stack 里
				container.setItem(i, stack.split(container.getMaxStackSize(stack)));
			}
		}

		// 有东西放进去了, 就告诉游戏箱子内容变了, 要存盘
		if (stack.getCount() != before) {
			container.setChanged();
		}
	}
}
