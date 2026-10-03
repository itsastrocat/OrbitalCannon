package com.orbitalcannon.passthrough.world;

import static com.orbitalcannon.passthrough.ipc.Protocol.*;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import com.orbitalcannon.passthrough.ipc.Bridge;
import com.orbitalcannon.passthrough.ipc.RingWriter;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import net.minecraft.core.BlockPos;

/**
 * Owns the Minecraft half of the voxel mirror: which sections GTA currently has, exactly which visible voxels it has
 * been told about in each, and the logic that keeps that picture correct.
 *
 * <ul>
 *   <li><b>Streaming:</b> sections within the requested radius of the player are scanned nearest-first under a wall-clock
 *       budget and sent as compact {@code SECTION_DATA} records (only the visible shell, 4 bytes per voxel).
 *   <li><b>Diffing:</b> per section the streamer remembers the id it last sent for every voxel, so a block change
 *       re-evaluates just that voxel and its six neighbours and emits only what actually differs.
 *   <li><b>Back-pressure:</b> if ring A is full nothing is half-sent; work is deferred and retried next tick.
 * </ul>
 *
 * <p>Server thread only.
 */
public final class SectionStreamer {
	private static final int HYSTERESIS = 8; // blocks of extra radius before a section is unloaded
	private static final int RESCAN_THRESHOLD = 512; // beyond this many changed voxels, diff whole sections instead
	private static final int RING_SLACK = 2 * (MAX_PAYLOAD + 64);

	private static final class Streamed {
		final int sx, sy, sz;
		final short[] sent = new short[4096];
		int count; // non-zero entries in sent, i.e. voxels GTA holds for this section
		int version;

		Streamed(int sx, int sy, int sz) {
			this.sx = sx;
			this.sy = sy;
			this.sz = sz;
		}
	}

	public static final class Stats {
		public long scans, scanNanos, sectionsSent, unloadsSent, updatesSent, deferrals, voxelsSent;
	}

	private final Bridge bridge;
	private final PaletteRegistry pal;
	private final VoxelSource src;
	private final SectionScanner scanner = new SectionScanner();
	private final Stats stats = new Stats();

	private final Long2ObjectOpenHashMap<Streamed> streamed = new Long2ObjectOpenHashMap<>();
	private final short[] wanted = new short[4096];
	private final int[] seenStamp = new int[65536];
	private int stamp;
	private int version;

	// candidate list, reused every tick
	private int[] cSx = new int[512], cSy = new int[512], cSz = new int[512];
	private long[] order = new long[512];
	private int pendingLoads;

	// sections whose whole shell must be re-diffed (mass edits, newly loaded neighbours)
	private final LongArrayFIFOQueue rescanQueue = new LongArrayFIFOQueue();
	private final LongOpenHashSet rescanSet = new LongOpenHashSet();

	// outgoing incremental updates, in order
	private int[] ux = new int[1024], uy = new int[1024], uz = new int[1024];
	private short[] uid = new short[1024];
	private byte[] ufl = new byte[1024];
	private int un;

	public SectionStreamer(Bridge bridge, PaletteRegistry pal, VoxelSource src) {
		this.bridge = bridge;
		this.pal = pal;
		this.src = src;
	}

	public Stats stats() {
		return stats;
	}

	public int streamedCount() {
		return streamed.size();
	}

	public int pendingLoads() {
		return pendingLoads;
	}

	public int bufferedUpdates() {
		return un;
	}

	/** Forget everything (new GTA session): GTA starts empty, so nothing is considered sent. */
	public void reset() {
		streamed.clear();
		rescanQueue.clear();
		rescanSet.clear();
		un = 0;
		pendingLoads = 0;
	}

	/** Chunk columns (packed {@code ChunkPos}) that must stay loaded for the current view; filled into {@code out}. */
	public static void desiredColumns(double px, double pz, float radH, LongSet out) {
		int r = (int) Math.ceil((radH + HYSTERESIS) / 16.0) + 1;
		int pcx = (int) Math.floor(px) >> 4, pcz = (int) Math.floor(pz) >> 4;
		for (int cx = pcx - r; cx <= pcx + r; cx++) {
			for (int cz = pcz - r; cz <= pcz + r; cz++) {
				double dx = axisDistance(px, cx << 4), dz = axisDistance(pz, cz << 4);
				if (dx * dx + dz * dz <= (radH + HYSTERESIS) * (radH + HYSTERESIS)) out.add(((long) cz << 32) | (cx & 0xFFFFFFFFL));
			}
		}
	}

	private static double axisDistance(double p, int minCorner) {
		return Math.max(Math.max(minCorner - p, 0.0), p - (minCorner + 16));
	}

	// ---- streaming sections ---------------------------------------------------------------------------------------

	/**
	 * Per-tick streaming step: unloads sections that left the view, runs queued re-diffs, then loads missing sections
	 * nearest-first until the time budget or the per-tick section cap is hit.
	 */
	public void update(double px, double py, double pz, float radH, float radV, long deadlineNanos, int maxSectionsPerTick) {
		final int minSy = src.minY() >> 4, maxSy = (src.maxYExclusive() - 1) >> 4;
		final int csx = (int) Math.floor(px) >> 4, csy = (int) Math.floor(py) >> 4, csz = (int) Math.floor(pz) >> 4;
		final int rh = (int) Math.ceil(radH / 16.0) + 1, rv = (int) Math.ceil(radV / 16.0) + 1;

		// Unload what drifted out of range (with hysteresis so the boundary does not thrash).
		var it = streamed.values().iterator();
		while (it.hasNext()) {
			Streamed s = it.next();
			if (!within(px, py, pz, s.sx, s.sy, s.sz, radH + HYSTERESIS, radV + HYSTERESIS)) {
				if (s.count > 0 && !bridge.sendSectionUnload(s.sx, s.sy, s.sz)) break; // ring full: try again next tick
				if (s.count > 0) stats.unloadsSent++;
				it.remove();
			}
		}

		// Candidates not yet streamed, nearest first.
		int n = 0;
		for (int sx = csx - rh; sx <= csx + rh; sx++) {
			for (int sz = csz - rh; sz <= csz + rh; sz++) {
				for (int sy = Math.max(minSy, csy - rv); sy <= Math.min(maxSy, csy + rv); sy++) {
					if (!within(px, py, pz, sx, sy, sz, radH, radV) || streamed.containsKey(sectionKey(sx, sy, sz))) continue;
					if (n == cSx.length) grow();
					cSx[n] = sx;
					cSy[n] = sy;
					cSz[n] = sz;
					double dx = axisDistance(px, sx << 4), dy = axisDistance(py, sy << 4), dz = axisDistance(pz, sz << 4);
					order[n] = ((long) Float.floatToIntBits((float) (dx * dx + dy * dy + dz * dz)) << 32) | n;
					n++;
				}
			}
		}
		pendingLoads = n;
		processRescans(deadlineNanos);
		if (n == 0) return;
		Arrays.sort(order, 0, n);
		int loaded = 0;
		for (int i = 0; i < n && loaded < maxSectionsPerTick; i++) {
			if (System.nanoTime() > deadlineNanos) break;
			int c = (int) order[i];
			int result = load(cSx[c], cSy[c], cSz[c]);
			if (result == LOAD_OK) {
				loaded++;
				pendingLoads--;
			} else if (result == LOAD_NO_SPACE) {
				stats.deferrals++;
				break; // preserve ordering: do not skip ahead of a section that is waiting for ring space
			}
			// LOAD_NOT_READY: its chunk is not loaded yet; leave it for a later tick and try the next one
		}
	}

	private static boolean within(double px, double py, double pz, int sx, int sy, int sz, double radH, double radV) {
		double dx = axisDistance(px, sx << 4), dz = axisDistance(pz, sz << 4), dy = axisDistance(py, sy << 4);
		return dx * dx + dz * dz <= radH * radH && dy <= radV;
	}

	private void grow() {
		int n = cSx.length * 2;
		cSx = Arrays.copyOf(cSx, n);
		cSy = Arrays.copyOf(cSy, n);
		cSz = Arrays.copyOf(cSz, n);
		order = Arrays.copyOf(order, n);
	}

	private static final int LOAD_OK = 0, LOAD_NOT_READY = 1, LOAD_NO_SPACE = 2;

	private int load(int sx, int sy, int sz) {
		long t0 = System.nanoTime();
		int n = scanner.scan(src, pal, sx, sy, sz, wanted);
		stats.scans++;
		stats.scanNanos += System.nanoTime() - t0;
		if (n < 0) return LOAD_NOT_READY;
		Streamed s = new Streamed(sx, sy, sz);
		s.version = ++version;
		if (n > 0) {
			if (!announceAll(wanted)) return LOAD_NO_SPACE;
			int records = (n + MAX_ENTRIES_PER_SECTION_MSG - 1) / MAX_ENTRIES_PER_SECTION_MSG;
			long need = (long) records * (MSG_HEADER_SIZE + SD_HDR_SIZE) + (long) n * VOXEL_ENTRY_SIZE + RING_SLACK;
			if (bridge.ringA().freeBytes() < need) return LOAD_NO_SPACE;
			writeSection(s, n);
			System.arraycopy(wanted, 0, s.sent, 0, 4096);
			s.count = n;
			stats.sectionsSent++;
			stats.voxelsSent += n;
		}
		streamed.put(sectionKey(sx, sy, sz), s);
		// Voxels on the shared faces of already-streamed neighbours may have become visible now that this chunk is known.
		refreshNeighbourFaces(s);
		return LOAD_OK;
	}

	private void writeSection(Streamed s, int total) {
		RingWriter ring = bridge.ringA();
		MemorySegment seg = bridge.segment();
		int idx = 0, remaining = total;
		boolean first = true;
		while (remaining > 0) {
			int k = Math.min(remaining, MAX_ENTRIES_PER_SECTION_MSG);
			long p = ring.begin(MSG_SECTION_DATA, SD_HDR_SIZE + k * VOXEL_ENTRY_SIZE);
			if (p < 0) throw new IllegalStateException("ring A rejected a record after the space check passed");
			remaining -= k;
			int flags = (first ? SEC_REPLACE : 0) | (remaining == 0 ? SEC_LAST : 0);
			first = false;
			seg.set(JAVA_INT_UNALIGNED, p + SD_SX, s.sx);
			seg.set(JAVA_INT_UNALIGNED, p + SD_SY, s.sy);
			seg.set(JAVA_INT_UNALIGNED, p + SD_SZ, s.sz);
			seg.set(JAVA_INT_UNALIGNED, p + SD_VERSION, s.version);
			seg.set(JAVA_SHORT_UNALIGNED, p + SD_COUNT, (short) k);
			seg.set(JAVA_SHORT_UNALIGNED, p + SD_FLAGS, (short) flags);
			seg.set(JAVA_INT_UNALIGNED, p + 20, 0);
			long e = p + SD_HDR_SIZE;
			for (int written = 0; written < k; idx++) {
				short id = wanted[idx];
				if (id == 0) continue;
				seg.set(JAVA_SHORT_UNALIGNED, e, (short) idx);
				seg.set(JAVA_SHORT_UNALIGNED, e + 2, id);
				e += VOXEL_ENTRY_SIZE;
				written++;
			}
		}
	}

	/** Queues PALETTE_ENTRY for every distinct id in {@code ids}. False if the ring filled up part-way (retry later). */
	private boolean announceAll(short[] ids) {
		if (++stamp == 0) {
			Arrays.fill(seenStamp, 0);
			stamp = 1;
		}
		for (int i = 0; i < 4096; i++) {
			int id = ids[i] & 0xFFFF;
			if (id == 0 || seenStamp[id] == stamp) continue;
			seenStamp[id] = stamp;
			if (!pal.announce(bridge, id)) return false;
		}
		return true;
	}

	private void refreshNeighbourFaces(Streamed fresh) {
		for (int f = 0; f < 6; f++) {
			int[] d = FACE_NORMAL[f];
			Streamed nb = streamed.get(sectionKey(fresh.sx + d[0], fresh.sy + d[1], fresh.sz + d[2]));
			if (nb == null) continue;
			// The layer of `nb` that touches `fresh`: its local coordinate on the shared axis is 0 if fresh is to its
			// west/down/north (d points from fresh toward nb, so the shared plane is nb's side facing -d).
			for (int a = 0; a < 16; a++) {
				for (int b = 0; b < 16; b++) {
					int lx, ly, lz;
					if (d[0] != 0) {
						lx = d[0] > 0 ? 0 : 15;
						ly = a;
						lz = b;
					} else if (d[1] != 0) {
						ly = d[1] > 0 ? 0 : 15;
						lx = a;
						lz = b;
					} else {
						lz = d[2] > 0 ? 0 : 15;
						lx = a;
						ly = b;
					}
					evalVoxel(nb, (nb.sx << 4) + lx, (nb.sy << 4) + ly, (nb.sz << 4) + lz, false);
				}
			}
		}
	}

	// ---- incremental changes --------------------------------------------------------------------------------------

	/**
	 * Re-evaluates the given changed block positions (and their six neighbours, whose visibility may have flipped) and
	 * queues exactly the differences. {@code urgent} tags the updates as caused by GTA's own request.
	 */
	public void applyChanges(LongSet positions, boolean urgent) {
		if (positions.isEmpty()) return;
		if (positions.size() > RESCAN_THRESHOLD) {
			for (LongIterator it = positions.iterator(); it.hasNext(); ) {
				long l = it.nextLong();
				int x = BlockPos.getX(l), y = BlockPos.getY(l), z = BlockPos.getZ(l);
				enqueueRescan(x >> 4, y >> 4, z >> 4);
				// A change on a section face can flip visibility of the neighbouring section's boundary voxels too.
				if ((x & 15) == 0) enqueueRescan((x >> 4) - 1, y >> 4, z >> 4);
				if ((x & 15) == 15) enqueueRescan((x >> 4) + 1, y >> 4, z >> 4);
				if ((y & 15) == 0) enqueueRescan(x >> 4, (y >> 4) - 1, z >> 4);
				if ((y & 15) == 15) enqueueRescan(x >> 4, (y >> 4) + 1, z >> 4);
				if ((z & 15) == 0) enqueueRescan(x >> 4, y >> 4, (z >> 4) - 1);
				if ((z & 15) == 15) enqueueRescan(x >> 4, y >> 4, (z >> 4) + 1);
			}
			return;
		}
		for (LongIterator it = positions.iterator(); it.hasNext(); ) {
			long l = it.nextLong();
			int x = BlockPos.getX(l), y = BlockPos.getY(l), z = BlockPos.getZ(l);
			evalAt(x, y, z, urgent);
			evalAt(x + 1, y, z, urgent);
			evalAt(x - 1, y, z, urgent);
			evalAt(x, y + 1, z, urgent);
			evalAt(x, y - 1, z, urgent);
			evalAt(x, y, z + 1, urgent);
			evalAt(x, y, z - 1, urgent);
		}
	}

	private void evalAt(int x, int y, int z, boolean urgent) {
		Streamed s = streamed.get(sectionKey(x >> 4, y >> 4, z >> 4));
		if (s != null) evalVoxel(s, x, y, z, urgent);
	}

	private void evalVoxel(Streamed s, int x, int y, int z, boolean urgent) {
		int local = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
		int want = Visibility.wantAt(src, pal, x, y, z);
		int have = s.sent[local];
		if (want == have) return;
		s.sent[local] = (short) want;
		if (have == 0) s.count++;
		else if (want == 0) s.count--;
		emit(x, y, z, want, urgent);
	}

	private void enqueueRescan(int sx, int sy, int sz) {
		long key = sectionKey(sx, sy, sz);
		if (streamed.containsKey(key) && rescanSet.add(key)) rescanQueue.enqueue(key);
	}

	private void processRescans(long deadlineNanos) {
		while (!rescanQueue.isEmpty() && System.nanoTime() < deadlineNanos) {
			long key = rescanQueue.dequeueLong();
			rescanSet.remove(key);
			Streamed s = streamed.get(key);
			if (s == null) continue;
			int n = scanner.scan(src, pal, s.sx, s.sy, s.sz, wanted);
			if (n < 0) continue;
			for (int i = 0; i < 4096; i++) {
				if (s.sent[i] == wanted[i]) continue;
				if (s.sent[i] == 0) s.count++;
				else if (wanted[i] == 0) s.count--;
				s.sent[i] = wanted[i];
				emit((s.sx << 4) | (i & 15), (s.sy << 4) | (i >> 8), (s.sz << 4) | ((i >> 4) & 15), wanted[i], false);
			}
		}
	}

	private void emit(int x, int y, int z, int id, boolean urgent) {
		if (un == ux.length) {
			int n = un * 2;
			ux = Arrays.copyOf(ux, n);
			uy = Arrays.copyOf(uy, n);
			uz = Arrays.copyOf(uz, n);
			uid = Arrays.copyOf(uid, n);
			ufl = Arrays.copyOf(ufl, n);
		}
		ux[un] = x;
		uy[un] = y;
		uz[un] = z;
		uid[un] = (short) id;
		ufl[un] = (byte) (urgent ? UPD_FROM_GTA : 0);
		un++;
	}

	/** Writes buffered updates to ring A in order, as many as fit. The rest stay buffered for the next call. */
	public void flushUpdates(int serverTick) {
		if (un == 0) return;
		RingWriter ring = bridge.ringA();
		MemorySegment seg = bridge.segment();
		int i = 0;
		while (i < un) {
			int k = Math.min(MAX_UPDATES_PER_MSG, un - i);
			boolean announced = true;
			for (int j = i; j < i + k; j++) {
				int id = uid[j] & 0xFFFF;
				if (id != 0 && !pal.announce(bridge, id)) {
					announced = false;
					k = j - i; // send what is announced so far, keep the rest for later
					break;
				}
			}
			if (k == 0) break;
			long p = ring.begin(MSG_VOXEL_UPDATES, VU_HDR_SIZE + k * VU_ENTRY_SIZE);
			if (p < 0) break;
			seg.set(JAVA_SHORT_UNALIGNED, p + VU_COUNT, (short) k);
			seg.set(JAVA_SHORT_UNALIGNED, p + VU_COUNT + 2, (short) 0);
			seg.set(JAVA_INT_UNALIGNED, p + VU_TICK, serverTick);
			long e = p + VU_HDR_SIZE;
			for (int j = i; j < i + k; j++, e += VU_ENTRY_SIZE) {
				seg.set(JAVA_INT_UNALIGNED, e, ux[j]);
				seg.set(JAVA_INT_UNALIGNED, e + 4, uy[j]);
				seg.set(JAVA_INT_UNALIGNED, e + 8, uz[j]);
				seg.set(JAVA_SHORT_UNALIGNED, e + 12, uid[j]);
				seg.set(JAVA_SHORT_UNALIGNED, e + 14, (short) (ufl[j] & 0xFF));
			}
			i += k;
			stats.updatesSent += k;
			if (!announced) break;
		}
		if (i > 0) {
			int rest = un - i;
			if (rest > 0) {
				System.arraycopy(ux, i, ux, 0, rest);
				System.arraycopy(uy, i, uy, 0, rest);
				System.arraycopy(uz, i, uz, 0, rest);
				System.arraycopy(uid, i, uid, 0, rest);
				System.arraycopy(ufl, i, ufl, 0, rest);
			}
			un = rest;
		}
	}

	/** Section coordinates of everything currently streamed, as {sx, sy, sz} triples. Diagnostics and tests. */
	java.util.List<int[]> streamedSections() {
		java.util.List<int[]> out = new java.util.ArrayList<>(streamed.size());
		for (Streamed s : streamed.values()) out.add(new int[] {s.sx, s.sy, s.sz});
		return out;
	}

	public boolean isStreamed(int x, int y, int z) {
		return streamed.containsKey(sectionKey(x >> 4, y >> 4, z >> 4));
	}

	/** What GTA currently has for the voxel (0 = nothing). Test and diagnostics hook. */
	public int sentAt(int x, int y, int z) {
		Streamed s = streamed.get(sectionKey(x >> 4, y >> 4, z >> 4));
		return s == null ? 0 : s.sent[(x & 15) | ((z & 15) << 4) | ((y & 15) << 8)];
	}

	public static long sectionKey(int sx, int sy, int sz) {
		return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
	}
}
