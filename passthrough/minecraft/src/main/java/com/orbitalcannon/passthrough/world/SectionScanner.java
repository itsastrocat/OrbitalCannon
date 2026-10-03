package com.orbitalcannon.passthrough.world;

import java.util.Arrays;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Computes the visible voxel shell of one 16x16x16 section: pass one classifies all 4096 cells into flat arrays, pass
 * two keeps the renderable ones that have at least one exposed face. Neighbours inside the section are array lookups;
 * only the six boundary layers fall through to the (chunk-cached) {@link VoxelSource}.
 *
 * <p>Reuses its scratch arrays, so one instance serves every scan on the server thread with no allocation.
 */
public final class SectionScanner {
	private static final byte RENDER = 1, OPAQUE = 2;

	private final short[] ids = new short[4096];
	private final byte[] flags = new byte[4096];

	/**
	 * Fills {@code out[4096]} with the wire id of each visible voxel (0 elsewhere) and returns how many there are, or
	 * -1 if the section's chunk is not loaded yet (try again later).
	 */
	public int scan(VoxelSource src, PaletteRegistry pal, int sx, int sy, int sz, short[] out) {
		Arrays.fill(out, (short) 0);
		final int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
		if (y0 >= src.maxYExclusive() || y0 + 16 <= src.minY()) return 0; // wholly above or below the world
		if (src.sectionKnownEmpty(sx, sy, sz)) return 0;
		if (src.get(x0, Math.max(y0, src.minY()), z0) == null) return -1; // chunk not loaded

		// Pass 1: classify every cell. Index order x | z<<4 | y<<8 matches the wire format and walks memory linearly.
		int any = 0;
		for (int ly = 0, idx = 0; ly < 16; ly++) {
			final int y = y0 + ly;
			for (int lz = 0; lz < 16; lz++) {
				final int z = z0 + lz;
				for (int lx = 0; lx < 16; lx++, idx++) {
					BlockState st = y < src.minY() || y >= src.maxYExclusive() ? null : src.get(x0 + lx, y, z);
					if (st == null) {
						ids[idx] = 0;
						flags[idx] = OPAQUE; // outside the world or unloaded: hides its neighbours
						continue;
					}
					PaletteRegistry.StateInfo info = pal.info(st);
					ids[idx] = (short) info.id;
					byte f = 0;
					if (info.renderable) f |= RENDER;
					if (info.opaque) f |= OPAQUE;
					flags[idx] = f;
					any |= f;
				}
			}
		}
		if ((any & RENDER) == 0) return 0;

		// Pass 2: keep renderable cells with an exposed face.
		int count = 0;
		for (int idx = 0; idx < 4096; idx++) {
			if ((flags[idx] & RENDER) == 0) continue;
			final int lx = idx & 15, lz = (idx >> 4) & 15, ly = idx >> 8;
			boolean visible =
				!(ly > 0 ? (flags[idx - 256] & OPAQUE) != 0 : Visibility.neighbourHides(src, pal, x0 + lx, y0 + ly - 1, z0 + lz))
				|| !(ly < 15 ? (flags[idx + 256] & OPAQUE) != 0 : Visibility.neighbourHides(src, pal, x0 + lx, y0 + ly + 1, z0 + lz))
				|| !(lz > 0 ? (flags[idx - 16] & OPAQUE) != 0 : Visibility.neighbourHides(src, pal, x0 + lx, y0 + ly, z0 + lz - 1))
				|| !(lz < 15 ? (flags[idx + 16] & OPAQUE) != 0 : Visibility.neighbourHides(src, pal, x0 + lx, y0 + ly, z0 + lz + 1))
				|| !(lx > 0 ? (flags[idx - 1] & OPAQUE) != 0 : Visibility.neighbourHides(src, pal, x0 + lx - 1, y0 + ly, z0 + lz))
				|| !(lx < 15 ? (flags[idx + 1] & OPAQUE) != 0 : Visibility.neighbourHides(src, pal, x0 + lx + 1, y0 + ly, z0 + lz));
			if (visible) {
				out[idx] = ids[idx];
				count++;
			}
		}
		return count;
	}
}
