package com.orbitalcannon.passthrough.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only view of the blocks the scanner sees. The production implementation reads loaded chunks of a
 * {@code ServerLevel}; tests supply a map-backed one built from real block states.
 */
public interface VoxelSource {
	/**
	 * The block state at a position, or {@code null} if that chunk is not loaded. Positions above the build height are
	 * air; positions below it are unloaded ({@code null}).
	 */
	BlockState get(int x, int y, int z);

	int minY();

	/** Exclusive upper bound of the build height. */
	int maxYExclusive();

	/** Cheap "this whole 16x16x16 section is air" check; lets the scanner skip the sky without reading 4096 blocks. */
	default boolean sectionKnownEmpty(int sx, int sy, int sz) {
		return false;
	}
}
