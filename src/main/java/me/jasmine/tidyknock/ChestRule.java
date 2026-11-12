package me.jasmine.tidyknock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

// 箱子收什么的规则
// labeled: 箱子上贴没贴展示框 (决定优先级用)
// items: 这个箱子愿意接收的物品种类, 空表示啥也不收
public record ChestRule(Storage storage, boolean labeled, Set<Item> items) {

	public static ChestRule of(Level level, Storage storage) {
		// 1: 箱子上贴了展示框, 就只收展示框里的物品
		// 大箱子两半上贴的展示框都算
		List<ItemFrame> frames = new ArrayList<>();
		for (BlockPos pos : storage.blocks()) {
			frames.addAll(framesOn(level, pos));
		}
		if (!frames.isEmpty()) {
			Set<Item> items = new HashSet<>();
			for (ItemFrame frame : frames) {
				ItemStack shown = frame.getItem();
				// 空展示框不贡献任何物品, 所以只贴空框的箱子排除掉
				if (!shown.isEmpty()) {
					items.add(shown.getItem());
				}
			}
			return new ChestRule(storage, true, items);
		}

		// 2: 没贴展示框, 就收箱子里已经有的物品种类
		Set<Item> items = new HashSet<>();
		Container container = storage.container();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty()) {
				items.add(stack.getItem());
			}
		}
		return new ChestRule(storage, false, items);
	}

	// 找出贴在这个方块上的所有展示框/荧光展示框
	private static List<ItemFrame> framesOn(Level level, BlockPos pos) {
		// 先把方块周围一圈的展示框都捞出来
		AABB around = new AABB(pos).inflate(1);
		// 展示框自己占着方块前面那一格, 朝向反过来一格才是它贴着的方块
		return level.getEntitiesOfClass(ItemFrame.class, around,
				frame -> frame.getPos().relative(frame.getDirection().getOpposite()).equals(pos));
	}
}
