package com.orbitalcannon.passthrough.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The single definition of "what GTA should draw at this voxel", shared by the bulk section scan and the per-block
 * incremental update path so the two can never disagree.
 *
 * <p>A voxel is sent only if it is renderable and at least one of its six neighbours does not hide it. A neighbour in
 * an unloaded chunk counts as opaque: we cannot see it, and drawing every voxel along the streaming boundary would
 * waste the entity budget on faces nobody can see.
 */
public final class Visibility {
	private Visibility() {
	}

	private static final int[][] DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	public static boolean neighbourHides(VoxelSource src, PaletteRegistry pal, int x, int y, int z) {
		BlockState n = src.get(x, y, z);
		return n == null || pal.info(n).opaque;
	}

	/** Wire id to display at the position (0 = nothing). */
	public static int wantAt(VoxelSource src, PaletteRegistry pal, int x, int y, int z) {
		BlockState st = src.get(x, y, z);
		if (st == null) return 0;
		PaletteRegistry.StateInfo info = pal.info(st);
		if (!info.renderable) return 0;
		for (int[] d : DIRS) {
			if (!neighbourHides(src, pal, x + d[0], y + d[1], z + d[2])) return info.id;
		}
		return 0;
	}
}
