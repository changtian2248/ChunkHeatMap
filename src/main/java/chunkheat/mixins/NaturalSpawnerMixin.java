package chunkheat.mixins;

import chunkheat.util.ChunkKeys;
import chunkheat.sampler.MsptSampler;
import chunkheat.sampler.TickCategory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * 刷怪计时。
 *
 * <p>与其他注入点不同：spawnForChunk 是静态方法，开始时刻只能存静态字段（它在主线程上按区块顺序
 * 调用，不会嵌套）；方法签名已含 level 与 chunk，无需自行计算坐标。
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@Unique
	private static long chunkheatSpawnStart;

	// 1.21.2 起参数由三个 boolean 改为 MobCategory 列表；两个形态各自分叉。
	@Inject(method = "spawnForChunk", at = @At("HEAD"))
	private static void chunkheatSpawnBegin(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
			boolean spawnFriendlies, boolean spawnMonsters, boolean rareSpawn, CallbackInfo ci) {
		chunkheatSpawnStart = MsptSampler.begin();
	}

	@Inject(method = "spawnForChunk", at = @At("RETURN"))
	private static void chunkheatSpawnEnd(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
			boolean spawnFriendlies, boolean spawnMonsters, boolean rareSpawn, CallbackInfo ci) {
		if (chunkheatSpawnStart == 0L) {
			return;
		}
		MsptSampler.end(TickCategory.SPAWN, level, ChunkKeys.pack(chunk.getPos()), chunkheatSpawnStart);
	}
}
