package me.jasmine.tidyknock;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TidyKnock implements ModInitializer {
	public static final String MOD_ID = "tidyknock";

	// 日志记录器, 往终端和日志文件里写字
	// 用 mod id 当名字, 这样一眼就能看出哪条信息是本模组打印的
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 游戏准备好加载模组时执行这里
		// 此时资源等部分内容可能还没初始化, 只适合做注册类的工作

		LOGGER.info("Hello Fabric world!");

		KnockHandler.register(); // 模组启动时把上面那个监听器真正挂上
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
