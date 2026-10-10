package chunkheat.compat.journeymap;

import chunkheat.ChunkHeatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 打开旅行地图的全屏地图界面。
 *
 * <p>打开全屏地图的唯一官方入口是旅行地图内部的 {@code UIManager.INSTANCE.getOrOpenFullscreenMap()}
 * —— 它不在公开的 v2 API 里（API 只暴露 {@code journeymap.api.*}）。自己 {@code new Fullscreen()} 会绕过
 * 旅行地图的静态状态与 UI 管理器初始化，不可取；因此这里用反射调那个内部入口，类名与方法名都只以
 * 字符串出现，编译期不依赖旅行地图本体，未装旅行地图时本类也不会被加载。
 */
public final class JourneyMapOpen {
	/** 全屏界面类的全名：已打开时不必再调一次。 */
	private static final String FULLSCREEN = "journeymap.client.ui.fullscreen.Fullscreen";

	private static final String UI_MANAGER = "journeymap.client.ui.UIManager";

	private JourneyMapOpen() {
	}

	/** 全屏地图未打开则打开；已打开则什么都不做（帧循环自会取出待定位目标）。 */
	public static void openIfClosed() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (screen != null && screen.getClass().getName().equals(FULLSCREEN)) {
			return;
		}
		try {
			Class<?> uiManager = Class.forName(UI_MANAGER);
			Object instance = uiManager.getField("INSTANCE").get(null);
			uiManager.getMethod("getOrOpenFullscreenMap").invoke(instance);
			ChunkHeatMod.LOGGER.info("已请求打开旅行地图全屏界面");
		} catch (ReflectiveOperationException | LinkageError e) {
			ChunkHeatMod.LOGGER.warn("ChunkHeat：打开旅行地图全屏界面失败（旅行地图版本可能不兼容）", e);
		}
	}
}
