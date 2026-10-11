package chunkheat.compat.journeymap;

import chunkheat.ChunkHeatMod;
import chunkheat.client.ChunkHeatClient;
import chunkheat.client.ChunkRef;
import chunkheat.client.ChunkTooltip;
import chunkheat.client.ClientConfig;
import chunkheat.client.ClientSnapshot;
import chunkheat.client.HeatColor;
import chunkheat.client.MapFocus;
import chunkheat.client.ScanProgress;
import chunkheat.client.ScanRing;
import chunkheat.client.ScanSummary;
import chunkheat.client.SourceListPanel;
import chunkheat.sampler.TicketCode;
import chunkheat.util.Ids;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.IOverlayListener;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.event.FullscreenDisplayEvent;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.client.fullscreen.ThemeButtonDisplay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.awt.geom.Point2D;

/**
 * 旅行地图后端：把逐区块 MSPT 热力图、悬停详情、进度圈、扫描总览与完整源列表呈现在旅行地图全屏界面上。
 *
 * <p>热力图本身不再手算坐标，而是为每个 chunk 创建一个 {@link PolygonOverlay}，
 * 让 JourneyMap 自己投影到地图坐标系，从而保证缩放/拖拽时始终对齐。
 *
 * <p>屏幕 UI（总览、源列表、悬停框、进度圈）在 {@link FullscreenRenderEvent} 中绘制时，
 * 先把 {@link GuiGraphics} 的 PoseStack 重置为 identity，避免受到 JourneyMap 残留变换的影响。
 */
public final class JourneyMapBackend {
	private static final String MOD_ID = "chunkheat";

	/** 面板与地图显示区左上角的间距。 */
	private static final int PANEL_MARGIN = 2;

	private static IClientAPI api;

	/** 扫描按钮：进度圈要贴着它画。 */
	private static IThemeButton scanButton;

	/** 三个面板的锚点（GUI 缩放坐标），每帧按地图显示区重算。 */
	private static int summaryX;
	private static int summaryY;
	private static int sourcesX;

	/** 源列表翻页按钮参数。 */
	private static final int PAGE_BTN = 12;
	private static final int PAGE_GAP = 2;
	private static int pageX;
	private static int pageY;

	/** 上次重建 overlay 时的快照状态。 */
	private static long lastSnapshotGeneration = -1;
	private static ResourceKey<Level> lastDimension;
	private static boolean lastPlayerAccepts = true;

	/** 当前鼠标悬停的 chunk（由 IOverlayListener 提供）。 */
	private static boolean hoverActive;
	private static int hoverChunkX;
	private static int hoverChunkZ;
	private static String hoverDimension;

	private JourneyMapBackend() {
	}

	/** 由插件入口调用一次：注册全部事件并挂接清屏钩子。 */
	public static void install(IClientAPI clientApi) {
		api = clientApi;
		FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(MOD_ID, JourneyMapBackend::onAddonButtons);
		FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(MOD_ID, JourneyMapBackend::onRender);
		FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(MOD_ID, JourneyMapBackend::onClick);
		ScanSummary.resetSourcesPanel();
		ChunkHeatMod.LOGGER.info("ChunkHeat：旅行地图插件已初始化");
	}

	/** 强制下一次渲染时重建 overlay（例如显示层级设置改变后）。 */
	public static void forceRebuild() {
		lastSnapshotGeneration = -1;
		lastDimension = null;
	}

	/** 立即移除所有已创建的 overlay；不清空 last* 状态。 */
	public static void clearOverlays() {
		if (api != null) {
			try {
				api.removeAll(MOD_ID, DisplayType.Polygon);
			} catch (Throwable t) {
				ChunkHeatMod.LOGGER.warn("ChunkHeat：移除旅行地图叠加层失败", t);
			}
		}
	}

	// —— 按钮 ——

	private static void onAddonButtons(FullscreenDisplayEvent.AddonButtonDisplayEvent event) {
		ThemeButtonDisplay display = event.getThemeButtonDisplay();
		try {
			scanButton = display.addThemeButton("chunkheat.button.scan", icon("scan"),
					button -> ChunkHeatClient.onButtonPress());
			scanButton.setTooltip("chunkheat.button.scan.tip");

			IThemeButton clear = display.addThemeButton("chunkheat.button.clear", icon("close"),
					button -> ChunkHeatClient.onClearPress());
			clear.setTooltip("chunkheat.button.clear");

			IThemeButton config = display.addThemeButton("chunkheat.button.config", icon("config"),
					button -> ChunkHeatClient.onConfigPress(Minecraft.getInstance().screen));
			config.setTooltip("chunkheat.button.config");

			display.addThemeToggleButton("chunkheat.button.summary", icon("collapse"),
					ClientConfig.summaryExpanded, button -> ChunkHeatClient.onSummaryToggle());
		} catch (Throwable t) {
			ChunkHeatMod.LOGGER.warn("ChunkHeat：往旅行地图加按钮失败，本次忽略", t);
		}
	}

	// —— 画面 ——

	private static void onRender(FullscreenRenderEvent event) {
		IFullscreen fullscreen = event.getFullscreen();
		UIState state = fullscreen.getUiState();
		if (state == null || !state.active || state.blockBounds == null || state.displayBounds == null) {
			return;
		}
		GuiGraphics graphics = event.getGraphics();
		int mouseX = event.getMouseX();
		int mouseY = event.getMouseY();

		updateOverlays(state);
		applyPendingFocus(fullscreen, state);

		computePanelAnchors(state);

		// 屏幕 UI 必须在 identity 坐标系下绘制，避免被 JourneyMap 的 PoseStack 变换影响
		graphics.pose().pushPose();
		graphics.pose().setIdentity();
		try {
			drawScanRing(graphics);
			drawTooltip(graphics, state, mouseX, mouseY);
			drawPanels(graphics, mouseX, mouseY);
		} finally {
			graphics.pose().popPose();
		}
	}

	/** 根据快照代次与维度变化重建 PolygonOverlay。 */
	private static void updateOverlays(UIState state) {
		ResourceKey<Level> dim = state.dimension;
		long gen = ClientSnapshot.generation();
		boolean accepts = api.playerAccepts(MOD_ID, DisplayType.Polygon);
		if (gen == lastSnapshotGeneration && dim.equals(lastDimension) && accepts == lastPlayerAccepts) {
			return;
		}
		lastSnapshotGeneration = gen;
		lastDimension = dim;
		lastPlayerAccepts = accepts;

		clearOverlays();
		if (!accepts) {
			return;
		}

		String dimId = Ids.id(dim);
		ClientSnapshot.Chunk[] chunks = ClientSnapshot.get(dimId);
		if (chunks == null) {
			return;
		}

		float redPoint = HeatColor.redPoint(ClientSnapshot.heaviestMspt(dimId));
		int shown = 0;
		int failed = 0;
		for (ClientSnapshot.Chunk chunk : chunks) {
			ShapeProperties props = shapeProperties(chunk, redPoint);
			BlockPos nw = new BlockPos(chunk.x1(), 0, chunk.z1());
			BlockPos ne = new BlockPos(chunk.x2(), 0, chunk.z1());
			BlockPos se = new BlockPos(chunk.x2(), 0, chunk.z2());
			BlockPos sw = new BlockPos(chunk.x1(), 0, chunk.z2());
			MapPolygon polygon = new MapPolygon(nw, ne, se, sw);
			PolygonOverlay overlay = new PolygonOverlay(MOD_ID, dim, props, polygon);
			overlay.setDisplayOrder(ClientConfig.overlayDisplayOrder);
			overlay.setActiveUIs(Context.UI.Fullscreen);
			overlay.setActiveMapTypes(Context.MapType.values());
			overlay.setOverlayListener(OVERLAY_LISTENER);
			try {
				api.show(overlay);
				shown++;
			} catch (Exception e) {
				if (failed == 0) {
					ChunkHeatMod.LOGGER.warn("ChunkHeat：显示 PolygonOverlay 失败", e);
				}
				failed++;
			}
		}
		if (failed > 0) {
			ChunkHeatMod.LOGGER.warn("ChunkHeat：{} 个 PolygonOverlay 显示失败", failed);
		}
		ChunkHeatMod.LOGGER.debug("ChunkHeat：旅行地图叠加层已重建，维度 {}，{} 个区块，失败 {}", dimId, shown, failed);
	}

	/** 根据 chunk 数据构造 fill / stroke。 */
	private static ShapeProperties shapeProperties(ClientSnapshot.Chunk chunk, float redPoint) {
		int rgb;
		float opacity;
		if (ClientConfig.showWeakGray && chunk.loadLevel() >= HeatColor.WEAK_LOAD_LEVEL && !chunk.timed()) {
			int argb = HeatColor.idleArgb();
			rgb = argb & 0xFFFFFF;
			opacity = ((argb >>> 24) & 0xFF) / 255.0f;
		} else {
			int argb = HeatColor.heatArgb(chunk.mspt(), redPoint, ClientConfig.fillAlpha);
			rgb = argb & 0xFFFFFF;
			opacity = ((argb >>> 24) & 0xFF) / 255.0f;
		}
		boolean center = TicketCode.isCenter(chunk.loadTicket()) || TicketCode.isCenter(chunk.simTicket());
		return new ShapeProperties()
				.setFillColor(rgb)
				.setFillOpacity(opacity)
				.setStrokeColor(HeatColor.CENTER_RGB)
				.setStrokeOpacity(center ? 1.0f : 0.0f)
				.setStrokeWidth(1.0f)
				.setStrokePosition(ShapeProperties.StrokePosition.INSIDE);
	}

	/** 所有 chunk 共享同一个监听器，hover / click 时更新静态状态。 */
	private static final IOverlayListener OVERLAY_LISTENER = new IOverlayListener() {
		@Override
		public void onMouseMove(UIState state, Point2D.Double mouse, BlockPos pos) {
			if (pos == null) {
				hoverActive = false;
				return;
			}
			hoverChunkX = pos.getX() >> 4;
			hoverChunkZ = pos.getZ() >> 4;
			hoverDimension = Ids.id(state.dimension);
			hoverActive = true;
		}

		@Override
		public void onMouseOut(UIState state, Point2D.Double mouse, BlockPos pos) {
			hoverActive = false;
		}

		@Override
		public boolean onMouseClick(UIState state, Point2D.Double mouse, BlockPos pos, int button, boolean doubleClick) {
			if (doubleClick || button != 0 || pos == null) {
				return false;
			}
			int cx = pos.getX() >> 4;
			int cz = pos.getZ() >> 4;
			MapFocus.request(Ids.id(state.dimension), cx, cz);
			return true;
		}
	};

	/** 面板锚点。 */
	private static void computePanelAnchors(UIState state) {
		int maxX = Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 40);
		int maxY = Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 16);
		summaryX = Mth.clamp(PANEL_MARGIN, 0, maxX);
		summaryY = Mth.clamp(PANEL_MARGIN, 0, maxY);
		sourcesX = summaryX + ScanSummary.panelSize()[0] + PANEL_MARGIN;
		pageX = summaryX;
		pageY = summaryY + ScanSummary.panelSize()[1] + 4;
	}

	/** 进度圈画在扫描按钮自身的边框上。 */
	private static void drawScanRing(GuiGraphics graphics) {
		if (!ScanProgress.active() || scanButton == null) {
			return;
		}
		Button button = scanButton.getButton();
		if (button == null) {
			return;
		}
		int size = Math.min(button.getWidth(), button.getHeight());
		ScanRing.draw(graphics, ScanProgress.fraction(), button.getX(), button.getY(), size);
	}

	private static void drawTooltip(GuiGraphics graphics, UIState state, int mouseX, int mouseY) {
		if (!hoverActive || hoverDimension == null) {
			return;
		}
		if (!hoverDimension.equals(Ids.id(state.dimension))) {
			return;
		}
		if (ScanSummary.overPanel(mouseX, mouseY, summaryX, summaryY)
				|| SourceListPanel.overPanel(mouseX, mouseY, sourcesX, summaryY)) {
			return;
		}
		if (ClientSnapshot.get(hoverDimension) == null) {
			return;
		}
		ChunkTooltip.draw(graphics, mouseX, mouseY, ChunkTooltip.lines(
				ClientSnapshot.find(hoverDimension, hoverChunkX, hoverChunkZ),
				hoverChunkX, hoverChunkZ, ClientSnapshot.windowTicks()));
	}

	private static void drawPanels(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!ClientConfig.summaryExpanded) {
			return;
		}
		ScanSummary.draw(graphics, summaryX, summaryY, mouseX, mouseY);
		if (ScanSummary.sourcesPanelOpen()) {
			SourceListPanel.draw(graphics, sourcesX, summaryY, mouseX, mouseY);
			drawPaging(graphics, mouseX, mouseY);
		}
	}

	private static void drawPaging(GuiGraphics graphics, int mouseX, int mouseY) {
		if (ScanSummary.sourceRows().isEmpty()) {
			return;
		}
		drawPageButton(graphics, pageX, pageY, true, mouseX, mouseY);
		drawPageButton(graphics, pageX + PAGE_BTN + PAGE_GAP, pageY, false, mouseX, mouseY);
	}

	private static void drawPageButton(GuiGraphics graphics, int x, int y, boolean up, int mouseX, int mouseY) {
		boolean hover = mouseX >= x && mouseX < x + PAGE_BTN && mouseY >= y && mouseY < y + PAGE_BTN;
		graphics.fill(x, y, x + PAGE_BTN, y + PAGE_BTN, hover ? 0xC0000000 : 0x80000000);
		drawTriangle(graphics, x, y, PAGE_BTN, up, 0xFFFFFFFF);
	}

	private static void drawTriangle(GuiGraphics graphics, int x, int y, int size, boolean up, int color) {
		int rows = size - 4;
		for (int i = 0; i < rows; i++) {
			int w = up ? (i * 2 + 1) : ((rows - 1 - i) * 2 + 1);
			int cx = x + size / 2;
			int top = y + 2 + i;
			graphics.fill(cx - w / 2, top, cx - w / 2 + w, top + 1, color);
		}
	}

	private static boolean inPageButton(int mouseX, int mouseY, int x, int y) {
		return mouseX >= x && mouseX < x + PAGE_BTN && mouseY >= y && mouseY < y + PAGE_BTN;
	}

	// —— 交互 ——

	private static void onClick(FullscreenMapEvent.ClickEvent event) {
		if (event.getStage() != FullscreenMapEvent.Stage.PRE || event.getButton() != 0) {
			return;
		}
		if (!ClientConfig.summaryExpanded) {
			return;
		}
		int mouseX = (int) event.getMouseX();
		int mouseY = (int) event.getMouseY();
		if (ScanSummary.hitFold(mouseX, mouseY, summaryX, summaryY)) {
			ScanSummary.toggleSourcesPanel();
			event.cancel();
			return;
		}
		if (ScanSummary.sourcesPanelOpen() && !ScanSummary.sourceRows().isEmpty()) {
			int inside = sourcesX + 4;
			int insideY = summaryY + 4;
			if (inPageButton(mouseX, mouseY, pageX, pageY)) {
				SourceListPanel.scroll(inside, insideY, sourcesX, summaryY, 1);
				event.cancel();
				return;
			}
			if (inPageButton(mouseX, mouseY, pageX + PAGE_BTN + PAGE_GAP, pageY)) {
				SourceListPanel.scroll(inside, insideY, sourcesX, summaryY, -1);
				event.cancel();
				return;
			}
		}
		ChunkRef target = SourceListPanel.hitTarget(mouseX, mouseY, sourcesX, summaryY);
		if (target == null) {
			target = ScanSummary.hitTarget(mouseX, mouseY, summaryX, summaryY);
		}
		if (target != null) {
			MapFocus.request(target.dimension(), target.chunkX(), target.chunkZ());
			event.cancel();
		}
	}

	/** 源列表的滚轮：鼠标在面板上则翻动并返回 true。 */
	public static boolean onScroll(int mouseX, int mouseY, int direction) {
		if (!ScanSummary.sourcesPanelOpen() || direction == 0) {
			return false;
		}
		return SourceListPanel.scroll(mouseX, mouseY, sourcesX, summaryY, direction);
	}

	/** 落地聊天告警的定位请求：换维度（必要时）并把地图中心移到目标区块。 */
	private static void applyPendingFocus(IFullscreen fullscreen, UIState state) {
		if (!MapFocus.awaiting()) {
			return;
		}
		ChunkRef target = MapFocus.consume();
		ResourceKey<Level> key = dimensionKey(target.dimension());
		if (key != null && !key.equals(state.dimension)) {
			if (state.mapType != null) {
				fullscreen.updateMapType(state.mapType, state.chunkY, key);
			}
			MapFocus.request(target.dimension(), target.chunkX(), target.chunkZ());
			return;
		}
		fullscreen.centerOn(target.chunkX() * 16 + 8, target.chunkZ() * 16 + 8);
		ChunkHeatMod.LOGGER.info("旅行地图定位 {} 区块 ({}, {})",
				target.dimension(), target.chunkX(), target.chunkZ());
	}

	private static ResourceKey<Level> dimensionKey(String dimension) {
		try {
			return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(dimension));
		} catch (Exception e) {
			return null;
		}
	}

	private static ResourceLocation icon(String name) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/gui/jm_" + name + ".png");
	}
}
