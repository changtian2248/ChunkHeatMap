package chunkheat.client;

/**
 * 热力配色的纯数学：与任何地图模组无关，Xaero 与旅行地图两个后端共用同一套阈值与斜坡。
 *
 * <p>单独成类是为了让旅行地图后端不必触碰 {@link MapOverlay} —— 后者 import 了 Xaero 的
 * {@code MapRenderHelper}，未装 Xaero 时加载它会抛 {@code NoClassDefFoundError}。
 *
 * <p>填色规则：测到过耗时 → 按 ms/tick 铺绿黄红；阈值与透明度每帧从 {@link ClientConfig} 现读。
 */
public final class HeatColor {
	/**
	 * 黄点在红点上的位置（1/3）：颜色只保留红点一个可调参数，黄点由它推出，相对模式下同样跟随本次
	 * 最重的区块。默认红阈值 1.5 时黄点为 0.50。
	 */
	private static final float YELLOW_KNEE = 1.0f / 3.0f;

	/** 弱加载且无耗时区块的填充色（灰）与不透明度。 */
	public static final int IDLE_GRAY = 0xB3;

	public static final double IDLE_ALPHA = 0.3;

	/** 加载源中心蓝框的颜色（不透明，与绿黄红热力色区分）。 */
	public static final int CENTER_RGB = 0x2080FF;

	/** 达到该加载等级即弱加载（与 {@code ChunkLevel.isBlockTicking} 的分界一致）。 */
	public static final int WEAK_LOAD_LEVEL = 32;

	private HeatColor() {
	}

	/**
	 * 当前帧的红点：耗时达到它即为最高等级的红。
	 *
	 * <p>固定模式（默认）取设置中的红色阈值，跨次可比；相对模式取当前显示维度、本次快照中最重的
	 * 区块。无任何耗时测得时退回固定阈值，保证结果恒大于 0，可作分母。
	 */
	public static float redPoint(float heaviest) {
		if (!ClientConfig.relativeColor) {
			return (float) ClientConfig.redAt;
		}
		return heaviest > 0.0f ? heaviest : (float) ClientConfig.redAt;
	}

	/** 黄点 = 红点 × {@link #YELLOW_KNEE}；红点恒大于 0，可作分母。 */
	public static float yellowAt(float redPoint) {
		return redPoint * YELLOW_KNEE;
	}

	/** 红色分量：黄点以下为绿→黄斜坡，以上为黄→红斜坡。 */
	public static float red(float mspt, float redPoint) {
		float knee = yellowAt(redPoint);
		return mspt <= knee ? mspt / knee : 1.0f;
	}

	/** 绿色分量：与 {@link #red} 配成同一条斜坡（0 → 绿，黄点 → 黄，≥红点 → 红）。 */
	public static float green(float mspt, float redPoint) {
		float knee = yellowAt(redPoint);
		return mspt <= knee ? 1.0f : 1.0f - Math.min(1.0f, (mspt - knee) / (redPoint - knee));
	}

	/**
	 * 一个区块的 ARGB 填充色。
	 *
	 * @param alpha 不透明度（0~1），来自设置
	 */
	public static int heatArgb(float mspt, float redPoint, double alpha) {
		int a = clamp255((int) Math.round(alpha * 255));
		int r = clamp255((int) Math.round(red(mspt, redPoint) * 255));
		int g = clamp255((int) Math.round(green(mspt, redPoint) * 255));
		return (a << 24) | (r << 16) | (g << 8);
	}

	/** 弱加载「仅加载、无计时」区块的灰。 */
	public static int idleArgb() {
		int a = clamp255((int) Math.round(IDLE_ALPHA * 255));
		return (a << 24) | (IDLE_GRAY << 16) | (IDLE_GRAY << 8) | IDLE_GRAY;
	}

	/** 加载源中心蓝框的颜色（不透明）。 */
	public static int centerArgb() {
		return 0xFF000000 | CENTER_RGB;
	}

	private static int clamp255(int v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}
}
