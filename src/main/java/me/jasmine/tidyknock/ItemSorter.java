package me.jasmine.tidyknock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// 这一部分负责把背包里的物品真正搬进箱子
public final class ItemSorter {

	private ItemSorter() {
	}

	// 一次归位的结果
	// moved: 成功归位的物品种类
	// leftover: 没地方放, 还留在身上的物品种类 (没有匹配的箱子, 或者箱子满了)
	// touched: 收到了东西的箱子
	public record Result(Set<Item> moved, Set<Item> leftover, Set<Storage> touched) {
	}

	// rules 要事先排好优先级, 越靠前的箱子越先被塞
	public static Result sortInto(Player player, List<ChestRule> rules) {
		Inventory inventory = player.getInventory();
		Result result = new Result(new HashSet<>(), new HashSet<>(), new HashSet<>());

		// 处理 0~35 格: 0~8 是快捷栏, 9~35 是背包
		// 36 往后是盔甲和副手, 不动
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || isKept(inventory, slot, stack)) {
				continue;
			}

			// 先记下是什么物品: 整组都塞进去后 stack 会变空, 就看不出原来是什么了
			Item item = stack.getItem();
			for (ChestRule rule : rules) {
				if (stack.isEmpty()) {
					break;
				}
				if (rule.items().contains(item) && insert(rule.storage().container(), stack)) {
					result.moved().add(item);
					result.touched().add(rule.storage());
				}
			}
			// 塞不进去的部分还留在 stack 里, 也就是原地留在背包, 不会掉出来
			if (!stack.isEmpty()) {
				result.leftover().add(item);
			}
		}

		inventory.setChanged();
		return result;
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
	// 返回这次有没有塞进去东西
	private static boolean insert(Container container, ItemStack stack) {
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

		if (stack.getCount() == before) {
			return false;
		}
		// 告诉游戏箱子内容变了, 要存盘
		container.setChanged();
		return true;
	}
}
