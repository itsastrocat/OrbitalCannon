package com.orbitalcannon.passthrough.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The scanner and the per-voxel visibility rule against a brute-force oracle, on real Minecraft block states. */
class ScannerTest {
	@BeforeAll
	static void bootstrap() {
		McBootstrap.init();
	}

	private static BlockState st(Block b) {
		return b.defaultBlockState();
	}

	@Test
	void classificationOfCommonBlocksMatchesWhatWeWantToDraw() {
		PaletteRegistry pal = new PaletteRegistry();
		Block[] drawn = {Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
			Blocks.GLASS, Blocks.OAK_LEAVES, Blocks.IRON_BLOCK, Blocks.BEDROCK};
		for (Block b : drawn) assertTrue(pal.info(st(b)).renderable, b + " should be drawn");
		Block[] notDrawn = {Blocks.AIR, Blocks.WATER, Blocks.LAVA, Blocks.TORCH, Blocks.SHORT_GRASS, Blocks.POPPY, Blocks.RAIL, Blocks.MOSS_CARPET,
			Blocks.SNOW, Blocks.COBWEB, Blocks.LADDER, Blocks.OAK_DOOR, Blocks.OAK_FENCE};
		for (Block b : notDrawn) assertFalse(pal.info(st(b)).renderable, b + " should not be drawn");
		// The fill rule in numbers: what is drawn is at least 40% solid, what is dropped is thin or sparse.
		assertEquals(1.0, PaletteRegistry.collisionFill(st(Blocks.STONE)), 1e-9);
		assertEquals(0.5, PaletteRegistry.collisionFill(st(Blocks.OAK_SLAB)), 1e-9);
		assertTrue(PaletteRegistry.collisionFill(st(Blocks.LADDER)) < 0.25);
		assertTrue(PaletteRegistry.collisionFill(st(Blocks.OAK_STAIRS)) >= 0.7);
		// Slabs and stairs are drawn (as cubes); opacity is what decides whether they hide their neighbours.
		assertTrue(pal.info(st(Blocks.OAK_SLAB)).renderable);
		assertTrue(pal.info(st(Blocks.OAK_STAIRS)).renderable);
		assertTrue(pal.info(st(Blocks.STONE)).opaque);
		assertFalse(pal.info(st(Blocks.GLASS)).opaque, "glass does not hide what is behind it");
		assertFalse(pal.info(st(Blocks.OAK_SLAB)).opaque);
		// Placeability and metadata.
		int stone = pal.info(st(Blocks.STONE)).id;
		assertEquals("minecraft:stone", pal.entry(stone).name);
		assertTrue((pal.entry(stone).flags & com.orbitalcannon.passthrough.ipc.Protocol.PAL_PLACEABLE) != 0);
		assertTrue(pal.entry(stone).rgb != 0);
		assertEquals(Blocks.STONE, pal.blockFor(stone));
		// One id per block type, shared by all of its states.
		assertEquals(pal.info(st(Blocks.OAK_STAIRS)).id, pal.info(Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP)).id);
		assertNotEquals(stone, pal.info(st(Blocks.DIRT)).id);
		assertEquals(0, pal.info(st(Blocks.AIR)).id);
		assertEquals(null, pal.entry(0));
		assertEquals(null, pal.entry(60000));
	}

	/** Brute-force oracle straight from the definition: renderable and some neighbour does not hide it. */
	private static int oracle(MapVoxelSource src, PaletteRegistry pal, int x, int y, int z) {
		BlockState s = src.get(x, y, z);
		if (s == null || !pal.info(s).renderable) return 0;
		int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] v : d) {
			BlockState n = src.get(x + v[0], y + v[1], z + v[2]);
			boolean hides = n == null || pal.info(n).opaque;
			if (!hides) return pal.info(s).id;
		}
		return 0;
	}

	private static void assertSectionMatchesOracle(MapVoxelSource src, PaletteRegistry pal, int sx, int sy, int sz) {
		SectionScanner sc = new SectionScanner();
		short[] out = new short[4096];
		int n = sc.scan(src, pal, sx, sy, sz, out);
		assertTrue(n >= 0, "section should be loaded");
		int counted = 0;
		for (int i = 0; i < 4096; i++) {
			int x = (sx << 4) | (i & 15), z = (sz << 4) | ((i >> 4) & 15), y = (sy << 4) | (i >> 8);
			int want = oracle(src, pal, x, y, z);
			assertEquals(want, out[i], "voxel " + x + "," + y + "," + z);
			if (want != 0) counted++;
		}
		assertEquals(counted, n);
	}

	@Test
	void solidCubeShowsOnlyItsSurfaceAcrossSectionBoundaries() {
		PaletteRegistry pal = new PaletteRegistry();
		MapVoxelSource src = new MapVoxelSource(-64, 320);
		src.loadAll(-32, -32, 47, 47);
		for (int x = 5; x < 25; x++)
			for (int y = 60; y < 80; y++)
				for (int z = 5; z < 25; z++) src.set(x, y, z, st(Blocks.STONE));
		int visible = 0;
		for (int sx = 0; sx <= 1; sx++)
			for (int sy = 3; sy <= 4; sy++)
				for (int sz = 0; sz <= 1; sz++) {
					assertSectionMatchesOracle(src, pal, sx, sy, sz);
					short[] out = new short[4096];
					visible += new SectionScanner().scan(src, pal, sx, sy, sz, out);
				}
		// 20x20x20 cube: 8000 voxels, interior 18^3 = 5832 hidden, so exactly 2168 on the shell.
		assertEquals(2168, visible);
	}

	@Test
	void randomMixedWorldAgreesWithTheOracleEverywhere() {
		PaletteRegistry pal = new PaletteRegistry();
		MapVoxelSource src = new MapVoxelSource(-64, 320);
		src.loadAll(0, 0, 47, 47);
		Random rng = new Random(2024);
		Block[] palette = {Blocks.STONE, Blocks.DIRT, Blocks.GLASS, Blocks.OAK_LEAVES, Blocks.OAK_SLAB, Blocks.TORCH, Blocks.WATER, Blocks.SAND, Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS};
		for (int x = 0; x < 48; x++)
			for (int z = 0; z < 48; z++) {
				int h = 60 + rng.nextInt(10);
				for (int y = 50; y <= h; y++) src.set(x, y, z, st(palette[rng.nextInt(palette.length)]));
			}
		for (int sx = 0; sx < 3; sx++)
			for (int sz = 0; sz < 3; sz++)
				for (int sy = 3; sy <= 4; sy++) assertSectionMatchesOracle(src, pal, sx, sy, sz);
	}

	@Test
	void glassInsideStoneIsHiddenButStoneBesideGlassIsVisible() {
		PaletteRegistry pal = new PaletteRegistry();
		MapVoxelSource src = new MapVoxelSource(-64, 320);
		src.loadAll(0, 0, 15, 15);
		for (int x = 4; x <= 8; x++)
			for (int y = 60; y <= 64; y++)
				for (int z = 4; z <= 8; z++) src.set(x, y, z, st(Blocks.STONE));
		src.set(6, 62, 6, st(Blocks.GLASS));
		assertEquals(0, oracle(src, pal, 6, 62, 6), "glass completely enclosed by stone is invisible");
		assertEquals(0, Visibility.wantAt(src, pal, 6, 62, 6));
		// The stone voxels around the glass are interior (every neighbour opaque except the glass itself)...
		assertEquals(pal.info(st(Blocks.STONE)).id, Visibility.wantAt(src, pal, 6, 62, 5), "glass does not hide its neighbours' faces");
		// ...while a stone voxel whose six neighbours are all stone stays hidden.
		assertEquals(0, Visibility.wantAt(src, pal, 5, 62, 5));
	}

	@Test
	void unloadedNeighbourChunkCountsAsOpaqueAndUnloadedSectionIsReportedNotReady() {
		PaletteRegistry pal = new PaletteRegistry();
		MapVoxelSource src = new MapVoxelSource(-64, 320);
		src.loadAll(0, 0, 15, 15); // only chunk (0,0); everything around it is unloaded
		src.set(15, 64, 8, st(Blocks.STONE)); // on the east edge of the loaded chunk
		src.set(14, 64, 8, st(Blocks.STONE));
		// The stone at x=15 has its east neighbour in an unloaded chunk (treated as opaque) but air above/below: visible.
		assertTrue(Visibility.wantAt(src, pal, 15, 64, 8) != 0);
		SectionScanner sc = new SectionScanner();
		assertEquals(-1, sc.scan(src, pal, 5, 4, 5, new short[4096]), "section in an unloaded chunk is not ready");
		// A voxel whose only exposed side faces the unloaded chunk is NOT shown (we cannot see anything there).
		MapVoxelSource tiny = new MapVoxelSource(-64, 320);
		tiny.loadAll(0, 0, 15, 15);
		for (int y = 60; y <= 70; y++)
			for (int z = 0; z <= 15; z++) {
				for (int x = 0; x <= 15; x++) tiny.set(x, y, z, st(Blocks.STONE));
			}
		// x=15,y=65,z=8 is buried in stone except toward the unloaded east neighbour: stays hidden.
		assertEquals(0, Visibility.wantAt(tiny, pal, 15, 65, 8));
	}

	@Test
	void heightLimitsBehaveLikeSkyAndVoid() {
		PaletteRegistry pal = new PaletteRegistry();
		MapVoxelSource src = new MapVoxelSource(-64, 320);
		src.loadAll(0, 0, 15, 15);
		src.set(3, 319, 3, st(Blocks.STONE)); // top of the world: its upper face borders open sky
		src.set(3, -64, 3, st(Blocks.STONE)); // bottom of the world: below is void/unloaded
		assertTrue(Visibility.wantAt(src, pal, 3, 319, 3) != 0);
		assertEquals(null, src.get(3, -65, 3));
		SectionScanner sc = new SectionScanner();
		short[] out = new short[4096];
		assertEquals(0, sc.scan(src, pal, 0, 20, 0, out), "a section wholly above the world is empty");
		assertTrue(sc.scan(src, pal, 0, 19, 0, out) >= 1);
	}
}
