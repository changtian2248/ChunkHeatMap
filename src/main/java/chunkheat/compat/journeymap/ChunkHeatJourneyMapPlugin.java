package chunkheat.compat.journeymap;

import chunkheat.client.ClientConfig;
import chunkheat.client.ClientSnapshot;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * 旅行地图（JourneyMap）客户端插件入口。
 *
 * <p>由 JourneyMap 通过 {@link JourneyMapPlugin} 注解发现；Fabric 侧另有 {@code fabric.mod.json} 的
 * {@code journeymap} 入口点指向本类。本类是全模组唯一被 JourneyMap 加载的类，其余旅行地图相关代码
 * 都在其内部引用 —— 未装旅行地图时本类不会被类加载，也就不会拖入 v2 API 的类。
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class ChunkHeatJourneyMapPlugin implements IClientPlugin {
	@Override
	public void initialize(IClientAPI jmClientApi) {
		JourneyMapBackend.install(jmClientApi);
		// 清屏时按用户设置立即移除 overlay
		ClientSnapshot.setClearHook(() -> {
			if (ClientConfig.clearOverlaysImmediately) {
				JourneyMapBackend.clearOverlays();
			}
		});
		// 显示层级 / 颜色设置改变时立即重建 overlay
		ClientConfig.overlayRebuildHook = JourneyMapBackend::forceRebuild;
	}

	@Override
	public String getModId() {
		return "chunkheat";
	}
}
