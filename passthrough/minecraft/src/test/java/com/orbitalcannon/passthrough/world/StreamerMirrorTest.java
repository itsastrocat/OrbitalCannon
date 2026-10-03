package com.orbitalcannon.passthrough.world;

import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orbitalcannon.passthrough.ipc.Bridge;
import com.orbitalcannon.passthrough.ipc.Protocol;
import com.orbitalcannon.passthrough.ipc.RingReader;
import com.orbitalcannon.passthrough.ipc.SharedMemory;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the real SectionStreamer against a simulated GTA that decodes ring A exactly like the C++ VoxelStore does,
 * then demands the two pictures be identical after streaming, thousands of random edits, mass edits, view movement,
 * late chunk loading and ring back-pressure.
 */
class StreamerMirrorTest {
	@BeforeAll
	static void bootstrap() {
		McBootstrap.init();
	}

	/** The GTA side's world picture, built only from what arrives over the wire. */
	private static final class GtaMirror {
		final Long2IntOpenHashMap voxels = new Long2IntOpenHashMap();
		final Set<Integer> palette = new HashSet<>();
		int records, urgentUpdates, unloads;
		boolean everUsedUnannouncedId;

		void use(int id) {
			if (id != 0 && !palette.contains(id)) everUsedUnannouncedId = true;
		}

		void clearSection(int sx, int sy, int sz) {
			for (int lx = 0; lx < 16; lx++)
				for (int lz = 0; lz < 16; lz++)
					for (int ly = 0; ly < 16; ly++) voxels.remove(BlockPos.asLong((sx << 4) + lx, (sy << 4) + ly, (sz << 4) + lz));
		}

		void apply(int type, MemorySegment seg, long p, int len) {
			records++;
			switch (type) {
				case Protocol.MSG_PALETTE_ENTRY -> palette.add(seg.get(JAVA_SHORT_UNALIGNED, p + Protocol.PE_ID) & 0xFFFF);
				case Protocol.MSG_SECTION_DATA -> {
					int sx = seg.get(JAVA_INT_UNALIGNED, p + Protocol.SD_SX), sy = seg.get(JAVA_INT_UNALIGNED, p + Protocol.SD_SY), sz = seg.get(JAVA_INT_UNALIGNED, p + Protocol.SD_SZ);
					int count = seg.get(JAVA_SHORT_UNALIGNED, p + Protocol.SD_COUNT) & 0xFFFF;
					int flags = seg.get(JAVA_SHORT_UNALIGNED, p + Protocol.SD_FLAGS) & 0xFFFF;
					assertTrue(len >= Protocol.SD_HDR_SIZE + count * Protocol.VOXEL_ENTRY_SIZE);
					if ((flags & Protocol.SEC_REPLACE) != 0) clearSection(sx, sy, sz);
					for (int i = 0; i < count; i++) {
						long e = p + Protocol.SD_HDR_SIZE + (long) i * Protocol.VOXEL_ENTRY_SIZE;
						int local = seg.get(JAVA_SHORT_UNALIGNED, e) & 0xFFFF, id = seg.get(JAVA_SHORT_UNALIGNED, e + 2) & 0xFFFF;
						assertTrue(local < 4096 && id != 0);
						use(id);
						voxels.put(BlockPos.asLong((sx << 4) | (local & 15), (sy << 4) | (local >> 8), (sz << 4) | ((local >> 4) & 15)), id);
					}
				}
				case Protocol.MSG_SECTION_UNLOAD -> {
					clearSection(seg.get(JAVA_INT_UNALIGNED, p), seg.get(JAVA_INT_UNALIGNED, p + 4), seg.get(JAVA_INT_UNALIGNED, p + 8));
					unloads++;
				}
				case Protocol.MSG_VOXEL_UPDATES -> {
					int count = seg.get(JAVA_SHORT_UNALIGNED, p + Protocol.VU_COUNT) & 0xFFFF;
					for (int i = 0; i < count; i++) {
						long e = p + Protocol.VU_HDR_SIZE + (long) i * Protocol.VU_ENTRY_SIZE;
						int x = seg.get(JAVA_INT_UNALIGNED, e), y = seg.get(JAVA_INT_UNALIGNED, e + 4), z = seg.get(JAVA_INT_UNALIGNED, e + 8);
						int id = seg.get(JAVA_SHORT_UNALIGNED, e + 12) & 0xFFFF, flags = seg.get(JAVA_SHORT_UNALIGNED, e + 14) & 0xFFFF;
						use(id);
						if ((flags & Protocol.UPD_FROM_GTA) != 0) urgentUpdates++;
						if (id == 0) voxels.remove(BlockPos.asLong(x, y, z));
						else voxels.put(BlockPos.asLong(x, y, z), id);
					}
				}
				default -> {
				}
			}
		}
	}

	private String name;
	private Bridge mc;
	private SharedMemory gtaShm;
	private RingReader gtaReadsA;
	private final GtaMirror mirror = new GtaMirror();
	private PaletteRegistry pal;
	private MapVoxelSource world;
	private SectionStreamer streamer;

	@BeforeEach
	void open() throws IOException {
		name = "ocp_stream_test_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
		mc = Bridge.open(name, SharedMemory.Backend.FILE);
		gtaShm = SharedMemory.open(name, SharedMemory.Backend.FILE);
		gtaReadsA = new RingReader(gtaShm.segment(), Protocol.RING_A_CTL_OFF, Protocol.RING_A_OFF);
		pal = new PaletteRegistry();
		world = new MapVoxelSource(-64, 320);
		streamer = new SectionStreamer(mc, pal, world);
	}

	@AfterEach
	void close() throws IOException {
		mc.close();
		gtaShm.close();
		Files.deleteIfExists(com.orbitalcannon.passthrough.ipc.FileBackedMemoryAccess.resolve(name));
	}

	private void gtaDrain() {
		gtaReadsA.drain(mirror::apply, Integer.MAX_VALUE);
	}

	private void generateTerrain(int size) {
		Random rng = new Random(7);
		Block[] features = {Blocks.GLASS, Blocks.OAK_LEAVES, Blocks.TORCH, Blocks.WATER, Blocks.OAK_SLAB, Blocks.SAND};
		world.loadAll(0, 0, size - 1, size - 1);
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				int h = 60 + (int) (4 * Math.sin(x / 7.0) + 3 * Math.cos(z / 5.0));
				for (int y = 40; y < h; y++) world.set(x, y, z, Blocks.STONE.defaultBlockState());
				world.set(x, h, z, Blocks.DIRT.defaultBlockState());
				world.set(x, h + 1, z, Blocks.GRASS_BLOCK.defaultBlockState());
				if (rng.nextInt(12) == 0) world.set(x, h + 2, z, features[rng.nextInt(features.length)].defaultBlockState());
			}
		}
	}

	private static final long FAR = Long.MAX_VALUE / 2;

	private boolean anyVoxel(java.util.function.LongPredicate test) {
		for (var it = mirror.voxels.keySet().iterator(); it.hasNext(); ) {
			if (test.test(it.nextLong())) return true;
		}
		return false;
	}

	/** Streams everything within the radius, however many ticks that takes. */
	private void streamAll(double cx, double cy, double cz, float h, float v) {
		for (int i = 0; i < 400; i++) {
			streamer.update(cx, cy, cz, h, v, System.nanoTime() + FAR / 4, 64);
			streamer.flushUpdates(i);
			mc.commit();
			gtaDrain();
			if (streamer.pendingLoads() == 0 && streamer.bufferedUpdates() == 0) return;
		}
	}

	private void assertMirrorMatchesWorld() {
		Long2IntOpenHashMap expected = new Long2IntOpenHashMap();
		for (int[] s : streamer.streamedSections()) {
			for (int i = 0; i < 4096; i++) {
				int x = (s[0] << 4) | (i & 15), y = (s[1] << 4) | (i >> 8), z = (s[2] << 4) | ((i >> 4) & 15);
				int want = Visibility.wantAt(world, pal, x, y, z);
				if (want != 0) expected.put(BlockPos.asLong(x, y, z), want);
				assertEquals(want, streamer.sentAt(x, y, z), "streamer bookkeeping at " + x + "," + y + "," + z);
			}
		}
		assertEquals(expected.size(), mirror.voxels.size(), "GTA holds a different number of voxels than the world says are visible");
		for (var e : expected.long2IntEntrySet()) {
			assertEquals(e.getIntValue(), mirror.voxels.get(e.getLongKey()), "voxel " + BlockPos.of(e.getLongKey()));
		}
		assertFalse(mirror.everUsedUnannouncedId, "a palette id was used before its PALETTE_ENTRY");
	}

	@Test
	void initialStreamReproducesExactlyTheVisibleShell() {
		generateTerrain(96);
		streamAll(48, 62, 48, 40, 24);
		assertTrue(streamer.streamedCount() > 20, "streamed " + streamer.streamedCount());
		assertTrue(mirror.voxels.size() > 3000);
		assertMirrorMatchesWorld();
		// The shell is a tiny fraction of the volume: that is the whole point of culling before streaming.
		long solidVolume = world.blocks.size();
		assertTrue(mirror.voxels.size() * 3 < solidVolume, mirror.voxels.size() + " visible of " + solidVolume + " solid");
	}

	@Test
	void thousandsOfRandomEditsKeepGtaExactlyInSync() {
		generateTerrain(96);
		streamAll(48, 62, 48, 40, 24);
		Random rng = new Random(1234);
		Block[] palette = {Blocks.AIR, Blocks.AIR, Blocks.STONE, Blocks.GLASS, Blocks.OAK_PLANKS, Blocks.TORCH, Blocks.WATER, Blocks.OAK_SLAB, Blocks.DIRT};
		LongOpenHashSet dirty = new LongOpenHashSet();
		for (int round = 0; round < 60; round++) {
			dirty.clear();
			int edits = 1 + rng.nextInt(40);
			for (int i = 0; i < edits; i++) {
				int x = 20 + rng.nextInt(56), z = 20 + rng.nextInt(56), y = 55 + rng.nextInt(16);
				world.set(x, y, z, palette[rng.nextInt(palette.length)].defaultBlockState());
				dirty.add(BlockPos.asLong(x, y, z));
			}
			boolean gta = round % 3 == 0;
			streamer.applyChanges(dirty, gta);
			streamer.flushUpdates(1000 + round);
			mc.commit();
			gtaDrain();
			if (round % 10 == 9) assertMirrorMatchesWorld();
		}
		assertMirrorMatchesWorld();
		assertTrue(mirror.urgentUpdates > 0, "updates caused by GTA's own requests are tagged so it can render them first");
	}

	@Test
	void breakingABlockRevealsTheOnesBehindItAndPlacingHidesThem() {
		generateTerrain(48);
		streamAll(24, 62, 24, 30, 24);
		int x = 24, z = 24;
		int top = 0;
		for (int y = 100; y > 40; y--) if (world.get(x, y, z) != null && !world.get(x, y, z).isAir()) { top = y; break; }
		int below = top - 1;
		assertEquals(0, Visibility.wantAt(world, pal, x, below - 1, z), "buried block is not streamed");
		assertEquals(0, mirror.voxels.get(BlockPos.asLong(x, below - 1, z)));
		world.set(x, top, z, MapVoxelSource.AIR);
		world.set(x, below, z, MapVoxelSource.AIR);
		LongOpenHashSet dirty = new LongOpenHashSet();
		dirty.add(BlockPos.asLong(x, top, z));
		dirty.add(BlockPos.asLong(x, below, z));
		streamer.applyChanges(dirty, true);
		streamer.flushUpdates(5);
		mc.commit();
		gtaDrain();
		assertTrue(mirror.voxels.get(BlockPos.asLong(x, below - 1, z)) != 0, "the block under the hole is now exposed and sent");
		assertMirrorMatchesWorld();
		world.set(x, below, z, Blocks.STONE.defaultBlockState());
		dirty.clear();
		dirty.add(BlockPos.asLong(x, below, z));
		streamer.applyChanges(dirty, true);
		streamer.flushUpdates(6);
		mc.commit();
		gtaDrain();
		assertMirrorMatchesWorld();
	}

	@Test
	void massEditsFallBackToWholeSectionRediffs() {
		generateTerrain(96);
		streamAll(48, 62, 48, 40, 24);
		LongOpenHashSet dirty = new LongOpenHashSet();
		Random rng = new Random(5);
		for (int i = 0; i < 4000; i++) {
			int x = 10 + rng.nextInt(70), z = 10 + rng.nextInt(70), y = 56 + rng.nextInt(12);
			world.set(x, y, z, rng.nextBoolean() ? MapVoxelSource.AIR : Blocks.COBBLESTONE.defaultBlockState());
			dirty.add(BlockPos.asLong(x, y, z));
		}
		assertTrue(dirty.size() > 512);
		streamer.applyChanges(dirty, false);
		streamAll(48, 62, 48, 40, 24); // update() drains the queued re-diffs
		assertMirrorMatchesWorld();
	}

	@Test
	void movingTheViewUnloadsFarSectionsAndStreamsNewOnes() {
		generateTerrain(160);
		streamAll(30, 62, 30, 28, 24);
		assertTrue(anyVoxel(k -> BlockPos.getX(k) < 20));
		streamAll(130, 62, 130, 28, 24);
		assertMirrorMatchesWorld();
		assertTrue(mirror.unloads > 0);
		assertFalse(anyVoxel(k -> BlockPos.getX(k) < 20 && BlockPos.getZ(k) < 20), "the origin area was unloaded");
		assertTrue(anyVoxel(k -> BlockPos.getX(k) > 120 && BlockPos.getZ(k) > 120));
	}

	@Test
	void lateLoadedChunksRefreshBoundaryVoxelsOfAlreadyStreamedNeighbours() {
		generateTerrain(96);
		// Only the western half is loaded at first: the boundary voxels at x=47 face an unloaded neighbour (hidden).
		world.loadedColumns.removeIf(c -> net.minecraft.world.level.ChunkPos.getX(c) >= 3);
		streamAll(40, 62, 48, 40, 24);
		assertMirrorMatchesWorld();
		// The east half finishes loading; its sections stream in and the old boundary must be corrected.
		world.loadAll(48, 0, 95, 95);
		streamAll(40, 62, 48, 40, 24);
		assertMirrorMatchesWorld();
		// Boundary column that was previously suppressed is now correct: every exposed face along x=47 is drawn.
		int drawn = 0;
		for (int y = 40; y < 75; y++) for (int z = 0; z < 96; z++) if (mirror.voxels.get(BlockPos.asLong(47, y, z)) != 0) drawn++;
		assertTrue(drawn > 0);
	}

	@Test
	void backPressureNeverTearsASectionAndStreamingResumesWhenGtaCatchesUp() {
		generateTerrain(96);
		// Fill ring A (through the bridge's own writer, so cursors stay coherent) until almost no room is left.
		long i = 0;
		while (mc.ringA().freeBytes() > 60_000) mc.sendPing(i++);
		mc.commit();
		for (int tick = 0; tick < 20; tick++) {
			streamer.update(48, 62, 48, 40, 24, System.nanoTime() + FAR / 4, 64);
			streamer.flushUpdates(tick);
			mc.commit();
		}
		assertTrue(streamer.stats().deferrals > 0, "the streamer noticed the ring was full and deferred");
		assertTrue(streamer.pendingLoads() > 0);
		gtaDrain(); // GTA wakes up and consumes everything
		streamAll(48, 62, 48, 40, 24);
		assertEquals(0, streamer.pendingLoads());
		assertMirrorMatchesWorld(); // identical: nothing was torn, duplicated or reordered by the stall
	}

	@Test
	void resetMakesTheNextSessionStartFromScratch() {
		generateTerrain(48);
		streamAll(24, 62, 24, 30, 24);
		int before = streamer.streamedCount();
		assertTrue(before > 0);
		streamer.reset();
		assertEquals(0, streamer.streamedCount());
		pal.resetAnnouncements();
		mirror.voxels.clear();
		mirror.palette.clear();
		streamAll(24, 62, 24, 30, 24);
		assertEquals(before, streamer.streamedCount());
		assertMirrorMatchesWorld();
	}
}
