package me.jasmine.tidyknock.client;

import net.fabricmc.api.ClientModInitializer;

public class TidyKnockClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 客户端专用的入口, 适合放渲染这类只在客户端运行的逻辑
	}
}