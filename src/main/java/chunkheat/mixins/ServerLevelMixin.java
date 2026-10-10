package chunkheat.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import chunkheat.util.ChunkKeys;
import chunkheat.sampler.MsptSampler;
import chunkheat.sampler.TickCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 随机刻、计划刻、方块更新、方块事件、实体五类工作的计时。
 *
 * <p>每个注入点均以 HEAD 记开始、RETURN 记结束，差值即该方法自身的耗时。开始时刻存 {@code @Unique}
 * 字段而不用 ThreadLocal：这些方法只在服务端主线程调用，而 ThreadLocal 每次读写都要装箱一个 Long。
 *
 * <p>各 RETURN 注入点先判「开始时刻为 0（未在采样）」并提前返回，否则每次收尾都要白算一遍区块坐标
 * 打包与维度转换（{@link MsptSampler#end} 本就忽略 0，但参数已经求值）。乘客不重复计时，其耗时记在
 * 载具所在区块。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Unique
	private long chunkheatRandomTickStart;

	@Unique
	private long chunkheatBlockStart;

	@Unique
	private long chunkheatFluidStart;

	@Unique
	private long chunkheatEntityStart;

	@Unique
	private long chunkheatNeighborStart;

	/** 方块更新的嵌套层数（各入口共享）：只有最外层那次计时。 */
	@Unique
	private int chunkheatNeighborDepth;

	@Unique
	private long chunkheatBlockEventStart;

	@Inject(method = "tickChunk", at = @At("HEAD"))
	private void chunkheatRandomTickBegin(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		this.chunkheatRandomTickStart = MsptSampler.begin();
	}

	@Inject(method = "tickChunk", at = @At("RETURN"))
	private void chunkheatRandomTickEnd(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		if (this.chunkheatRandomTickStart == 0L) {
			return;
		}
		MsptSampler.end(TickCategory.RANDOM_TICK, this.chunkheatLevel(), ChunkKeys.pack(chunk.getPos()), this.chunkheatRandomTickStart);
	}

	@Inject(method = "tickBlock", at = @At("HEAD"))
	private void chunkheatBlockBegin(BlockPos pos, Block block, CallbackInfo ci) {
		this.chunkheatBlockStart = MsptSampler.begin();
	}

	@Inject(method = "tickBlock", at = @At("RETURN"))
	private void chunkheatBlockEnd(BlockPos pos, Block block, CallbackInfo ci) {
		if (this.chunkheatBlockStart == 0L) {
			return;
		}
		MsptSampler.end(TickCategory.SCHEDULED, this.chunkheatLevel(), ChunkKeys.pack(pos), this.chunkheatBlockStart);
	}

	/** 计划刻：流体。开始时刻与方块分开存储，两者嵌套时互不覆盖。 */
	@Inject(method = "tickFluid", at = @At("HEAD"))
	private void chunkheatFluidBegin(BlockPos pos, Fluid fluid, CallbackInfo ci) {
		this.chunkheatFluidStart = MsptSampler.begin();
	}

	@Inject(method = "tickFluid", at = @At("RETURN"))
	private void chunkheatFluidEnd(BlockPos pos, Fluid fluid, CallbackInfo ci) {
		if (this.chunkheatFluidStart == 0L) {
			return;
		}
		MsptSampler.end(TickCategory.SCHEDULED, this.chunkheatLevel(), ChunkKeys.pack(pos), this.chunkheatFluidStart);
	}

	@Inject(method = "tickNonPassenger", at = @At("HEAD"))
	private void chunkheatEntityBegin(Entity entity, CallbackInfo ci) {
		this.chunkheatEntityStart = MsptSampler.begin();
	}

	@Inject(method = "tickNonPassenger", at = @At("RETURN"))
	private void chunkheatEntityEnd(Entity entity, CallbackInfo ci) {
		if (this.chunkheatEntityStart == 0L) {
			return;
		}
		MsptSampler.end(TickCategory.ENTITY, this.chunkheatLevel(), ChunkKeys.pack(entity.chunkPosition()), this.chunkheatEntityStart);
	}

	/**
	 * 方块更新的各入口共享同一深度计数：{@code updateNeighborsAt}（六向派发）、
	 * {@code updateNeighborsAtExceptFromFacing}（排除某方向）与 {@code neighborChanged}（单点通知，三参与
	 * BlockState 五参两个重载）。红石连锁在派发过程中会绕回这些方法，故只记最外层那一次（嵌套调用的耗时
	 * 已含在外层中，逐个记账会重复累加），区块取最外层那次的位置。
	 *
	 * <p>用 {@code @WrapMethod} 而非两次 {@code @Inject}（HEAD / RETURN）：深度计数须在 try-finally
	 * 中还原——目标方法抛异常时 RETURN 注入不会执行，计数将永久失衡，此后所有红石耗时都不再记录。
	 */
	@Unique
	private void chunkheatNeighborBegin() {
		if (this.chunkheatNeighborDepth++ == 0) {
			this.chunkheatNeighborStart = MsptSampler.begin();
		}
	}

	/** 退出任一方块更新入口：最外层关窗并记账。 */
	@Unique
	private void chunkheatNeighborEnd(BlockPos pos) {
		if (--this.chunkheatNeighborDepth == 0 && this.chunkheatNeighborStart != 0L) {
			MsptSampler.end(TickCategory.NEIGHBOR_UPDATE, this.chunkheatLevel(), ChunkKeys.pack(pos),
					this.chunkheatNeighborStart);
		}
	}

	// 1.21.2 起红石派发改用 Orientation 参数（neighborChanged 原有的 BlockPos 参数被其替换），以下各入口
	// 的签名均在此处分叉。
	@WrapMethod(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V")
	private void chunkheatNeighbor(BlockPos pos, Block sourceBlock, Operation<Void> original) {
		this.chunkheatNeighborBegin();
		try {
			original.call(pos, sourceBlock);
		} finally {
			this.chunkheatNeighborEnd(pos);
		}
	}

	/** 六向派发，排除一个方向（观察者、中继器、红石粉一类方块的直呼路径）。 */
	@WrapMethod(method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V")
	private void chunkheatNeighborExceptFromFacing(BlockPos pos, Block sourceBlock, Direction except,
			Operation<Void> original) {
		this.chunkheatNeighborBegin();
		try {
			original.call(pos, sourceBlock, except);
		} finally {
			this.chunkheatNeighborEnd(pos);
		}
	}

	/** 单点邻居通知。 */
	@WrapMethod(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
			+ "Lnet/minecraft/core/BlockPos;)V")
	private void chunkheatNeighborChanged(BlockPos pos, Block sourceBlock, BlockPos fromPos, Operation<Void> original) {
		this.chunkheatNeighborBegin();
		try {
			original.call(pos, sourceBlock, fromPos);
		} finally {
			this.chunkheatNeighborEnd(pos);
		}
	}

	/** 单点邻居通知，调用方持有目标方块的状态。 */
	@WrapMethod(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
			+ "Lnet/minecraft/core/BlockPos;Z)V")
	private void chunkheatNeighborChangedState(BlockState state, BlockPos pos, Block sourceBlock, BlockPos fromPos,
			boolean movedByPiston, Operation<Void> original) {
		this.chunkheatNeighborBegin();
		try {
			original.call(state, pos, sourceBlock, fromPos, movedByPiston);
		} finally {
			this.chunkheatNeighborEnd(pos);
		}
	}

	/**
	 * 方块事件：由 {@code ServerLevel.tick} 中的 runBlockEvents 调起，不在 tickChunk 内，与本类其余
	 * 各注入点不重叠。
	 *
	 * <p>该方法返回 boolean（事件是否被处理），故回调必须是 {@link CallbackInfoReturnable}：目标有
	 * 返回值而回调写成 CallbackInfo 时，Mixin 会在 Bootstrap 阶段报 InvalidInjectionException。
	 * 此处只要时间，不修改返回值（未声明 cancellable）。
	 */
	@Inject(method = "doBlockEvent", at = @At("HEAD"))
	private void chunkheatBlockEventBegin(BlockEventData eventData, CallbackInfoReturnable<Boolean> cir) {
		this.chunkheatBlockEventStart = MsptSampler.begin();
	}

	@Inject(method = "doBlockEvent", at = @At("RETURN"))
	private void chunkheatBlockEventEnd(BlockEventData eventData, CallbackInfoReturnable<Boolean> cir) {
		if (this.chunkheatBlockEventStart == 0L) {
			return;
		}
		MsptSampler.end(TickCategory.BLOCK_EVENT, this.chunkheatLevel(), ChunkKeys.pack(eventData.pos()),
				this.chunkheatBlockEventStart);
	}

	/** Mixin 内 this 不具备 ServerLevel 静态类型，须转换后才能作为参数传递。 */
	@Unique
	private ServerLevel chunkheatLevel() {
		return (ServerLevel) (Object) this;
	}
}
