package chunkheat.compat.journeymap.mixins;

import chunkheat.compat.journeymap.JourneyMapBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 旅行地图全屏界面的鼠标滚轮：鼠标压在右侧完整源列表上时把滚轮截胡过来翻列表，否则放行给地图缩放。
 *
 * <p>旅行地图的 API 没有滚轮事件（只有点击 / 拖动 / 移动 / 右键菜单），故只能注入其全屏界面类的
 * {@code mouseScrolled}（1.21.1 的 intermediary 名 {@code method_25401}）。
 *
 * <p>目标类按名字字符串指定，不写死类型：本配置声明为 {@code required:false}，未装旅行地图时
 * 目标类找不到即整条跳过，游戏照常启动。
 */
@Mixin(targets = "journeymap.client.ui.fullscreen.Fullscreen", remap = false)
public abstract class FullscreenScrolledMixin {
	@Inject(method = "method_25401", at = @At("HEAD"), remap = false, cancellable = true)
	private void chunkheat$scrollSources(double mouseX, double mouseY, double scrollX, double scrollY,
			CallbackInfoReturnable<Boolean> cir) {
		if (JourneyMapBackend.onScroll((int) mouseX, (int) mouseY, (int) Math.signum(scrollY))) {
			// 已消费：返回 true，别让地图拿去缩放
			cir.setReturnValue(true);
		}
	}
}
