package com.orbitalcannon.passthrough.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Map-backed world of real block states with an explicit set of loaded chunk columns. */
final class MapVoxelSource implements VoxelSource {
	static final BlockState AIR = Blocks.AIR.defaultBlockState();

	final Map<Long, BlockState> blocks = new HashMap<>();
	final LongOpenHashSet loadedColumns = new LongOpenHashSet();
	final int min, maxExclusive;

	MapVoxelSource(int min, int maxExclusive) {
		this.min = min;
		this.maxExclusive = maxExclusive;
	}

	void loadAll(int minX, int minZ, int maxX, int maxZ) {
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) loadedColumns.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
		}
	}

	void set(int x, int y, int z, BlockState st) {
		if (st.isAir()) blocks.remove(BlockPos.asLong(x, y, z));
		else blocks.put(BlockPos.asLong(x, y, z), st);
	}

	@Override
	public BlockState get(int x, int y, int z) {
		if (y >= maxExclusive) return AIR;
		if (y < min) return null;
		if (!loadedColumns.contains(net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4))) return null;
		return blocks.getOrDefault(BlockPos.asLong(x, y, z), AIR);
	}

	@Override
	public int minY() {
		return min;
	}

	@Override
	public int maxYExclusive() {
		return maxExclusive;
	}
}
