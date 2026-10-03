package com.orbitalcannon.passthrough.world;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * {@link VoxelSource} over a live {@link ServerLevel}. Only fully loaded chunks are read (never forcing a load), with
 * a one-chunk cache because scans and neighbour checks hit the same chunk thousands of times in a row.
 *
 * <p>Server thread only. Call {@link #invalidate()} before each batch of work: chunks can be replaced between ticks.
 */
public final class LevelVoxelSource implements VoxelSource {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final ServerLevel level;
	private final ServerChunkCache cache;
	private final int minY;
	private final int maxY;
	private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
	private LevelChunk chunk;

	public LevelVoxelSource(ServerLevel level) {
		this.level = level;
		this.cache = level.getChunkSource();
		this.minY = level.getMinY();
		this.maxY = level.getMinY() + level.getHeight();
	}

	public ServerLevel level() {
		return level;
	}

	public void invalidate() {
		chunk = null;
		cx = cz = Integer.MIN_VALUE;
	}

	private LevelChunk chunkAt(int blockX, int blockZ) {
		int ncx = blockX >> 4, ncz = blockZ >> 4;
		if (chunk != null && ncx == cx && ncz == cz) return chunk;
		LevelChunk c = cache.getChunkNow(ncx, ncz); // null unless fully loaded
		if (c != null) {
			chunk = c;
			cx = ncx;
			cz = ncz;
		}
		return c;
	}

	@Override
	public BlockState get(int x, int y, int z) {
		if (y >= maxY) return AIR;
		if (y < minY) return null;
		LevelChunk c = chunkAt(x, z);
		if (c == null) return null;
		LevelChunkSection section = c.getSection(c.getSectionIndex(y));
		return section.getBlockState(x & 15, y & 15, z & 15);
	}

	@Override
	public int minY() {
		return minY;
	}

	@Override
	public int maxYExclusive() {
		return maxY;
	}

	@Override
	public boolean sectionKnownEmpty(int sx, int sy, int sz) {
		LevelChunk c = chunkAt(sx << 4, sz << 4);
		if (c == null) return false;
		int idx = c.getSectionIndexFromSectionY(sy);
		return idx >= 0 && idx < c.getSections().length && c.getSection(idx).hasOnlyAir();
	}
}
