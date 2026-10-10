package chunkheat.sampler;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import chunkheat.util.ChunkKeys;
import chunkheat.util.Ids;
import chunkheat.ChunkHeatMod;
import chunkheat.mixins.ChunkMapAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.LongToIntFunction;

/**
 * 反查每个区块的加载来源：即该区块被哪张加载票覆盖。
 *
 * <p>票的分布有两种：forced / portal / ender_pearl 等是稀疏的，只落在一个区块上；
 * player_loading 则是逐区块铺的，视距内每格一张。稀疏票靠等级传播覆盖周围：从持票区块向外传播
 * 值逐格 +1（传播值 = 票自身等级 + 到锚点的距离），某邻居的实际等级恰等于本票的传播值时，说明
 * 本票是覆盖它的（并列）最强来源，继续往外走。逐区块铺的票则每格自身即为持票区块，扩散退化为
 * 原地不动，结果仍正确。
 *
 * <p>扩散判据必须拿「本票传播值」与实际等级比较，不能拿实际等级自己逐格 +1：实际等级是全区所有
 * 票取最小后的结果，若本票被更强的票压住（比如传送门落在玩家模拟区内），实际等级里已看不出本票，
 * 照它 +1 会顺着强票的等级梯度一路扩散，把强票的半片区域误认成本票的覆盖区。本类不自算等级，
 * 一律读传播器给出的实际等级比对。
 *
 * <p>加载链与模拟链是两条独立的传播链，须各推一遍（玩家票自 1.21.8 起拆为 player_loading 与
 * player_simulation，同一区块上两条链的源头可能不同）。
 *
 * <p>结果按 {@link TicketCode} 的线格式编码：源头偏移、类型与存疑位打包进一个 int。
 */
public final class TicketSources {
	private TicketSources() {
	}

	/**
	 * 算出该维度、该条链上每个区块的来源。
	 *
	 * @param simulation false 走加载链，true 走模拟链
	 * @return 区块坐标 → 编码后的来源；该链一条票都没覆盖到的区块不在表里，由调用方判为存疑
	 */
	public static Long2IntOpenHashMap resolve(ServerLevel level, boolean simulation) {
		// 1.21.4 及以前的票体系与 1.21.5+ 不同（票表在 DistanceManager，无 doesLoad/doesSimulate，
		// 类型集合也不同），加载票来源暂不支持：返回空表，客户端只显示等级、不显示来源。
		return new Long2IntOpenHashMap();
	}




}
