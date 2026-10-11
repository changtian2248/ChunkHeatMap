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
import com.mojang.blaze3d.systems.RenderSystem;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.event.FullscreenDisplayEvent;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.client.fullscreen.ThemeButtonDisplay;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.awt.geom.Point2D;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 旅行地图后端：把逐区块 MSPT 热力图、悬停详情、进度圈、扫描总览与完整源列表画在旅行地图的全屏界面上。
 *
 * <p>与 Xaero 后端（Mixin 注入其渲染缓冲）不同，这里全部走旅行地图的官方 API：热力图直接用
 * {@link FullscreenRenderEvent} 拿到的 {@link GuiGraphics} 按屏幕坐标铺色块，按钮走
 * {@link FullscreenDisplayEvent.AddonButtonDisplayEvent}，悬停 / 点击走 {@link FullscreenMapEvent}。
 * 因为共用 {@link ClientSnapshot} 与 {@link ChunkTooltip} / {@link ScanSummary} 等界面类，两个后端
 * 的数据与文案完全一致。
 *
 * <p><b>坐标系（关键）</b>：旅行地图全屏地图的方块↔像素换算 {@code MapRenderer.getBlockPixelInGrid}
 * 用的是 {@code Window.getScreenWidth()/getScreenHeight()}（<b>窗口/物理像素</b>，即 {@code width}/{@code height}
 * 字段），而<b>不是</b> {@code getGuiScaledWidth()/getGuiScaledHeight()}（GUI 缩放坐标）。两者相差
 * {@code scaleFactor = JmUI.calculateScaleFactor()}（= {@code guiScale * getScreenWidth()/getWidth()}，
 * 全屏时）倍。证据：{@code Fullscreen.method_25402}（点击）先做
 * {@code new Point2D.Double(mouseX * scaleFactor, mouseY * scaleFactor)} 才交给渲染器。
 *
 * <p>换算（与旅行地图同式）：
 * <pre>
 *   px = getScreenWidth()  / 2 + blockX * blockSize + A   （A 为锚点，见下）
 *   py = getScreenHeight() / 2 + blockZ * blockSize + C
 * </pre>
 * 为万无一失，锚点直接取 {@code getBlockPixelInGrid(0,0)}（反射，精确）；取不到时退回
 * {@code getScreenWidth()/2 - centerBlockX * blockSize}。斜率 {@code blockSize = zoom / 512} 取
 * {@link UIState#blockSize}（= 渲染器的 {@code zoom / 512}）。
 *
 * <p>不能改用 {@link UIState#blockBounds} 反推：它在 {@code updateUIState} 里被向外扩张了 32 格
 * （{@code corner.offset(-32,0,-32)} / {@code offset(32,h,32)}），拿它当可见区左上角会造成整体偏移。
 * 同理 {@link UIState#displayBounds} 在无 viewport 时是 {@code (-32,-32,w+64,h+64)}。
 *
 * <p><b>UI（总览 / 源列表 / 悬停框 / 翻页钮）</b>：事件里的鼠标坐标是 GUI 缩放坐标，故这一整套 UI 在
 * {@code poseStack.scale(scaleFactor)} 里按 GUI 坐标绘制——否则会被画小、位置也错（实机上左侧面板被
 * 裁掉、文字变小正是此故）。进度圈例外：它贴旅行地图自己的按钮，而按钮坐标本就是物理像素，故在未缩放的
 * 空间里画。
 *
 * <p>{@code centerBlockX/Z} 不在公开 API 里（{@code UIState.mapCenter} 只到整数方块），故反射读
 * {@code Fullscreen.mapRenderer} 的 {@code getCenterBlockX()/getCenterBlockZ()} 取精确值；取不到时
 * 退回 {@code UIState.mapCenter}（误差 &lt; 1 格）。
 */
public final class JourneyMapBackend {
	private static final String MOD_ID = "chunkheat";

	/** 面板与地图显示区左上角的间距。 */
	private static final int PANEL_MARGIN = 2;

	private static IClientAPI api;

	/** 扫描按钮：进度圈要贴着它画，故留着读它当前的屏幕坐标。 */
	private static IThemeButton scanButton;

	/** 三个面板的锚点（GUI 缩放坐标），每帧按地图显示区重算。 */
	private static int summaryX;
	private static int summaryY;
	private static int sourcesX;

	/** 源列表的翻页按钮：在总览面板下方并列两个（上/下），与滚轮并存。 */
	private static final int PAGE_BTN = 12;
	private static final int PAGE_GAP = 2;

	private static int pageX;
	private static int pageY;

	/**
	 * 本帧换算用的地图中心（方块坐标，含小数）与像素比例。每帧渲染开头从渲染器刷新一次，
	 * 其余绘制 / 命中全部读它，避免逐区块反射。
	 */
	private static double frameCenterBlockX;
	private static double frameCenterBlockZ;
	private static double frameBlockSize;

	/**
	 * 本帧仿射换算 {@code px = affineAX + affineBX * blockX}、{@code py = affineAY + affineBY * blockZ}，
	 * 以及 GUI 缩放坐标 → 物理像素的倍率（{@code JmUI.calculateScaleFactor()}）。
	 */
	private static double affineAX;
	private static double affineAY;
	private static double affineBX = 1.0;
	private static double affineBY = 1.0;
	private static double frameScaleFactor = 1.0;

	/** 旅行地图的渲染器实例与反射句柄；只解析一次，失败后不再重试。 */
	private static Object mapRenderer;
	private static boolean rendererResolved;
	private static Method methodCenterX;
	private static Method methodCenterZ;
	private static Method methodBlockPixel;
	private static Method methodScaleFactor;

	private JourneyMapBackend() {
	}

	/** 由插件入口调用一次：注册全部事件。 */
	public static void install(IClientAPI clientApi) {
		api = clientApi;
		FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(MOD_ID, JourneyMapBackend::onAddonButtons);
		FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(MOD_ID, JourneyMapBackend::onRender);
		FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(MOD_ID, JourneyMapBackend::onClick);
		ScanSummary.resetSourcesPanel();
		ChunkHeatMod.LOGGER.info("ChunkHeat：旅行地图插件已初始化");
	}

	// —— 按钮 ——

	private static void onAddonButtons(FullscreenDisplayEvent.AddonButtonDisplayEvent event) {
		ThemeButtonDisplay display = event.getThemeButtonDisplay();
		try {
			// 标签与提示都传语言键：旅行地图的两者都支持 i18n 键（按客户端语言解析）
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
		String dimension = Ids.id(state.dimension);

		// 本帧的换算基准（与旅行地图 getBlockPixelInGrid 同源：物理像素 + 地图中心 + zoom/512）
		computeFrame(state);

		applyPendingFocus(fullscreen, state);

		// —— 地图空间（物理像素，未缩放）：热力图、蓝框、进度圈 ——
		drawHeat(graphics, state, dimension);
		drawCenterBorders(graphics, state, dimension);
		drawScanRing(graphics);

		// —— UI 空间：事件鼠标是 GUI 缩放坐标，整套 UI 缩放进同一空间绘制 ——
		computePanelAnchors(state);
		if (frameScaleFactor > 0.0 && Math.abs(frameScaleFactor - 1.0) > 1.0e-6) {
			graphics.pose().pushPose();
			graphics.pose().scale((float) frameScaleFactor, (float) frameScaleFactor, 1.0F);
			drawTooltip(graphics, state, dimension, mouseX, mouseY);
			drawPanels(graphics, mouseX, mouseY);
			graphics.pose().popPose();
		} else {
			drawTooltip(graphics, state, dimension, mouseX, mouseY);
			drawPanels(graphics, mouseX, mouseY);
		}
	}

	/** 每帧刷新换算基准：反射取锚点（{@code getBlockPixelInGrid(0,0)}）与 GUI→物理倍率。 */
	private static void computeFrame(UIState state) {
		resolveRenderer();
		frameCenterBlockX = centerBlockX(state);
		frameCenterBlockZ = centerBlockZ(state);
		frameBlockSize = state.blockSize;
		frameScaleFactor = scaleFactor();

		// 斜率即 blockSize；锚点直接取 getBlockPixelInGrid(0,0)（精确），取不到再用中心反推
		double anchorX = screenWidth() / 2.0 - frameCenterBlockX * frameBlockSize;
		double anchorY = screenHeight() / 2.0 - frameCenterBlockZ * frameBlockSize;
		if (mapRenderer != null && methodBlockPixel != null) {
			try {
				Point2D.Double origin = (Point2D.Double) methodBlockPixel.invoke(mapRenderer, 0.0, 0.0);
				anchorX = origin.x;
				anchorY = origin.y;
			} catch (Throwable ignored) {
				// 退回用中心反推的锚点
			}
		}
		affineAX = anchorX;
		affineAY = anchorY;
		affineBX = frameBlockSize;
		affineBY = frameBlockSize;
	}

	/** GUI 缩放坐标 → 物理像素的倍率（{@code JmUI.calculateScaleFactor()}；取不到时用窗口宽高反推）。 */
	private static double scaleFactor() {
		if (methodScaleFactor != null) {
			try {
				double factor = (double) methodScaleFactor.invoke(null);
				if (factor > 0.0) {
					return factor;
				}
			} catch (Throwable ignored) {
				// 用下面的反推
			}
		}
		int gui = guiScaledWidth();
		return gui > 0 ? (double) screenWidth() / gui : 1.0;
	}

	/** 热力图：每个区块一个按屏幕坐标铺的色块。 */
	private static void drawHeat(GuiGraphics graphics, UIState state, String dimension) {
		ClientSnapshot.Chunk[] chunks = ClientSnapshot.get(dimension);
		if (chunks == null) {
			return;
		}
		float redPoint = HeatColor.redPoint(ClientSnapshot.heaviestMspt(dimension));
		int screenW = screenWidth();
		int screenH = screenHeight();

		RenderSystem.enableBlend();
		try {
			for (ClientSnapshot.Chunk chunk : chunks) {
				int x1 = screenX(chunk.x1());
				int x2 = screenX(chunk.x2());
				int y1 = screenY(chunk.z1());
				int y2 = screenY(chunk.z2());
				// 完全在屏幕外就跳过（区块数可达数万，裁掉视野外的能省一大截）
				if (x2 < 0 || y2 < 0 || x1 > screenW || y1 > screenH) {
					continue;
				}
				int argb = ClientConfig.showWeakGray && chunk.loadLevel() >= HeatColor.WEAK_LOAD_LEVEL && !chunk.timed()
						? HeatColor.idleArgb()
						: HeatColor.heatArgb(chunk.mspt(), redPoint, ClientConfig.fillAlpha);
				graphics.fill(x1, y1, x2, y2, argb);
			}
		} finally {
			RenderSystem.disableBlend();
		}
	}

	/** 加载源中心：沿区块内沿画一圈蓝框（与 Xaero 后端同一套判据）。 */
	private static void drawCenterBorders(GuiGraphics graphics, UIState state, String dimension) {
		ClientSnapshot.Chunk[] chunks = ClientSnapshot.get(dimension);
		if (chunks == null) {
			return;
		}
		int thickness = Math.max(1, (int) Math.round(frameBlockSize));
		int color = HeatColor.centerArgb();
		for (ClientSnapshot.Chunk chunk : chunks) {
			if (!TicketCode.isCenter(chunk.loadTicket()) && !TicketCode.isCenter(chunk.simTicket())) {
				continue;
			}
			int x1 = screenX(chunk.x1());
			int x2 = screenX(chunk.x2());
			int y1 = screenY(chunk.z1());
			int y2 = screenY(chunk.z2());
			graphics.fill(x1, y1, x2, y1 + thickness, color);
			graphics.fill(x1, y2 - thickness, x2, y2, color);
			graphics.fill(x1, y1 + thickness, x1 + thickness, y2 - thickness, color);
			graphics.fill(x2 - thickness, y1 + thickness, x2, y2 - thickness, color);
		}
	}

	/**
	 * 面板锚点。不用 {@link UIState#displayBounds} 的原点：它在无 viewport 时是 {@code -32}，会把面板
	 * 顶到屏幕外（实机上左侧面板被裁掉正是此故）。改钉在屏幕左上角内缩一点，并夹进屏幕内。
	 */
	private static void computePanelAnchors(UIState state) {
		int maxX = Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 40);
		int maxY = Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 16);
		summaryX = Mth.clamp(PANEL_MARGIN, 0, maxX);
		summaryY = Mth.clamp(PANEL_MARGIN, 0, maxY);
		sourcesX = summaryX + ScanSummary.panelSize()[0] + PANEL_MARGIN;
		pageX = summaryX;
		pageY = summaryY + ScanSummary.panelSize()[1] + 4;
	}

	/** 进度圈画在扫描按钮自身的边框上；按钮坐标由旅行地图布局后给出。 */
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

	private static void drawTooltip(GuiGraphics graphics, UIState state, String dimension, int mouseX, int mouseY) {
		if (ScanSummary.overPanel(mouseX, mouseY, summaryX, summaryY)
				|| SourceListPanel.overPanel(mouseX, mouseY, sourcesX, summaryY)) {
			return;
		}
		if (ClientSnapshot.get(dimension) == null) {
			return;
		}
		// 屏幕 → 世界：仿射的逆变换。事件鼠标是 GUI 缩放坐标，先乘倍率换算到物理像素
		double pixelX = mouseX * frameScaleFactor;
		double pixelY = mouseY * frameScaleFactor;
		double worldX = affineBX == 0.0 ? frameCenterBlockX : (pixelX - affineAX) / affineBX;
		double worldZ = affineBY == 0.0 ? frameCenterBlockZ : (pixelY - affineAY) / affineBY;
		int chunkX = Mth.floor(worldX) >> 4;
		int chunkZ = Mth.floor(worldZ) >> 4;
		ChunkTooltip.draw(graphics, mouseX, mouseY, ChunkTooltip.lines(
				ClientSnapshot.find(dimension, chunkX, chunkZ), chunkX, chunkZ, ClientSnapshot.windowTicks()));
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

	/**
	 * 源列表下方的翻页按钮：与滚轮并存（有些玩家更习惯点按）。面板高度已知，故钉在总览面板正下方。
	 * 只在源列表展开、且有行时画。
	 */
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

	/** 用若干条水平细线拼一个小三角（不依赖字体里是否存在 ▲ / ▼ 字形）。 */
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
		// 翻页按钮：以面板内的一个固定点喂给 scroll()，与滚轮走同一条翻动逻辑
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

	/** 源列表的滚轮：鼠标在面板上则翻动并返回 true。由 {@code FullscreenScrolledMixin} 调用。 */
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
			// 维度切换不是即时的：把目标放回去，等下一帧切完再落地
			MapFocus.request(target.dimension(), target.chunkX(), target.chunkZ());
			return;
		}
		fullscreen.centerOn(target.chunkX() * 16 + 8, target.chunkZ() * 16 + 8);
		ChunkHeatMod.LOGGER.info("旅行地图定位 {} 区块 ({}, {})",
				target.dimension(), target.chunkX(), target.chunkZ());
	}

	// —— 几何与工具 ——

	/** 反射取得旅行地图渲染器及其换算方法；只尝试一次。 */
	private static void resolveRenderer() {
		if (rendererResolved) {
			return;
		}
		rendererResolved = true;
		try {
			Class<?> fullscreenClass = Class.forName("journeymap.client.ui.fullscreen.Fullscreen");
			Field field = fullscreenClass.getDeclaredField("mapRenderer");
			field.setAccessible(true);
			Object renderer = field.get(null);
			if (renderer == null) {
				return;
			}
			mapRenderer = renderer;
			methodCenterX = renderer.getClass().getMethod("getCenterBlockX");
			methodCenterZ = renderer.getClass().getMethod("getCenterBlockZ");
			methodBlockPixel = renderer.getClass().getMethod("getBlockPixelInGrid", double.class, double.class);
		} catch (Throwable t) {
			ChunkHeatMod.LOGGER.warn("ChunkHeat：取不到旅行地图渲染器的坐标换算方法，改用 UIState 近似", t);
		}
		try {
			Class<?> jmUi = Class.forName("journeymap.client.ui.component.screens.JmUI");
			methodScaleFactor = jmUi.getMethod("calculateScaleFactor");
		} catch (Throwable t) {
			ChunkHeatMod.LOGGER.warn("ChunkHeat：取不到旅行地图的 scaleFactor，改用窗口宽高反推", t);
		}
	}

	/** 地图中心方块 X（含小数）。取不到精确值时退回 {@code UIState.mapCenter}。 */
	private static double centerBlockX(UIState state) {
		if (mapRenderer != null) {
			try {
				return (double) methodCenterX.invoke(mapRenderer);
			} catch (Throwable ignored) {
				// 退回近似
			}
		}
		return state.mapCenter != null ? state.mapCenter.getX() : 0.0;
	}

	/** 地图中心方块 Z（含小数）。取不到精确值时退回 {@code UIState.mapCenter}。 */
	private static double centerBlockZ(UIState state) {
		if (mapRenderer != null) {
			try {
				return (double) methodCenterZ.invoke(mapRenderer);
			} catch (Throwable ignored) {
				// 退回近似
			}
		}
		return state.mapCenter != null ? state.mapCenter.getZ() : 0.0;
	}

	/**
	 * 旅行地图方块↔像素空间用的宽度：{@code Window.getScreenWidth()}（字段 {@code width}，物理像素），
	 * 与 {@code MapRenderer.getBlockPixelInGrid} 内部所取一致。<b>不是</b> {@code getGuiScaledWidth()}。
	 */
	private static int screenWidth() {
		return Minecraft.getInstance().getWindow().getScreenWidth();
	}

	/** 同 {@link #screenWidth()}，{@code Window.getScreenHeight()}。 */
	private static int screenHeight() {
		return Minecraft.getInstance().getWindow().getScreenHeight();
	}

	/** GUI 缩放坐标空间宽度（UI 那一套用它）。 */
	private static int guiScaledWidth() {
		return Minecraft.getInstance().getWindow().getGuiScaledWidth();
	}

	/** 方块坐标 → 物理像素 X。 */
	private static int screenX(double worldX) {
		return (int) Math.round(affineAX + affineBX * worldX);
	}

	/** 方块坐标 → 物理像素 Y。 */
	private static int screenY(double worldZ) {
		return (int) Math.round(affineAY + affineBY * worldZ);
	}

	private static ResourceKey<Level> dimensionKey(String dimension) {
		try {
			return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
		} catch (Exception e) {
			return null;
		}
	}

	private static ResourceLocation icon(String name) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/gui/jm_" + name + ".png");
	}
}
