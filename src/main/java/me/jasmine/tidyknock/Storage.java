package me.jasmine.tidyknock;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;

// 储物单位: 单箱子/木桶占 1 格, 大箱子是 2 格
// blocks: 它占的方块坐标, 用于找展示框要
// container: 它的物品格子 (大箱子就是两半拼起来的 54 格)
public record Storage(List<BlockPos> blocks, Container container) {
}
