package me.jasmine.tidyknock;

import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// 这一部分负责把背包里的物品真正搬进箱子
public final class ItemSorter {

	private ItemSorter() {
	}

	// rules 要事先排好优先级, 越靠前的箱子越先被塞
	// 返回一共搬走了多少个物品
	public static int sortInto(Player player, List<ChestRule> rules) {
		Inventory inventory = player.getInventory();
		int moved = 0;

		// 处理 0~35 格: 0~8 是快捷栏, 9~35 是背包
		// 36 往后是盔甲和副手, 不动
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || isKept(inventory, slot, stack)) {
				continue;
			}

			int before = stack.getCount();
			for (ChestRule rule : rules) {
				if (stack.isEmpty()) {
					break;
				}
				if (rule.items().contains(stack.getItem())) {
					insert(rule.storage().container(), stack);
				}
			}
			// 塞不进去的部分还留在 stack 里, 也就是原地留在背包, 不会掉出来
			moved += before - stack.getCount();
		}

		inventory.setChanged();
		return moved;
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

		// 告诉游戏箱子内容变了, 要存盘
		container.setChanged();
	}
}
