package com.orbitalcannon.passthrough.world;

import com.orbitalcannon.passthrough.ipc.Bridge;
import com.orbitalcannon.passthrough.ipc.Protocol;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Assigns the 16-bit voxel ids that travel over the wire and classifies block states for rendering.
 *
 * <p>Ids are per {@link Block} (every state of oak stairs shares one id, GTA gets one prop per voxel regardless), stable
 * for the JVM's lifetime, and announced to GTA lazily: a {@code PALETTE_ENTRY} always precedes the first record that
 * uses an id in a session. Classification is per {@link BlockState} and cached by identity, so the hot scan loop does
 * a single identity-hash lookup per voxel.
 */
public final class PaletteRegistry {
	/** What the streamer needs to know about one block state. */
	public static final class StateInfo {
		/** Wire id; 0 means "draw nothing" (air, liquids, plants, ...). */
		public final int id;
		/** Drawn as a 1 m cube prop. */
		public final boolean renderable;
		/** A full opaque cube: hides the faces of its neighbours, so fully enclosed voxels are never sent. */
		public final boolean opaque;

		StateInfo(int id, boolean renderable, boolean opaque) {
			this.id = id;
			this.renderable = renderable;
			this.opaque = opaque;
		}
	}

	/** One announced block type. */
	public static final class Entry {
		public final int id;
		public final Block block;
		public final String name;
		public final int flags;
		public final int rgb;
		boolean announced;

		Entry(int id, Block block, String name, int flags, int rgb) {
			this.id = id;
			this.block = block;
			this.name = name;
			this.flags = flags;
			this.rgb = rgb;
		}
	}

	private static final StateInfo INVISIBLE = new StateInfo(0, false, false);
	private static final StateInfo INVISIBLE_OPAQUE = new StateInfo(0, false, true);

	private final Reference2ObjectOpenHashMap<BlockState, StateInfo> states = new Reference2ObjectOpenHashMap<>(4096);
	private final Reference2IntOpenHashMap<Block> blockIds = new Reference2IntOpenHashMap<>();
	private final List<Entry> entries = new ArrayList<>(512);

	public PaletteRegistry() {
		blockIds.defaultReturnValue(0);
	}

	public StateInfo info(BlockState state) {
		StateInfo cached = states.get(state);
		return cached != null ? cached : compute(state);
	}

	/** A block is drawn if its collision body fills at least this fraction of its 1 m cell. */
	static final double MIN_FILL = 0.4;

	/**
	 * Pure classification, exposed for tests and for tuning what GTA gets to draw.
	 *
	 * <p>GTA only gets 1 m cube props, so the question is which blocks are substantial enough to deserve one. The rule:
	 * a full opaque cube, or a collision body that fills at least {@link #MIN_FILL} of the cell. That keeps stone, glass,
	 * leaves, slabs (0.5), stairs (0.75), chests and beds, and drops everything thin or sparse: plants, torches, rails,
	 * carpets, snow layers, ladders, doors, trapdoors, panes and fence posts. Fluids are never drawn.
	 */
	public static boolean isRenderable(BlockState st) {
		if (st.isAir() || st.getBlock() instanceof LiquidBlock) return false;
		return st.isSolidRender() || collisionFill(st) >= MIN_FILL;
	}

	/** Fraction of the unit cell occupied by the block's collision shape (its boxes are disjoint, so volumes just add). */
	static double collisionFill(BlockState st) {
		VoxelShape shape = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (shape.isEmpty()) return 0.0;
		double volume = 0.0;
		for (AABB b : shape.toAabbs()) volume += b.getXsize() * b.getYsize() * b.getZsize();
		return volume;
	}

	private StateInfo compute(BlockState st) {
		boolean opaque = !st.isAir() && st.isSolidRender();
		StateInfo info;
		if (!isRenderable(st)) {
			info = opaque ? INVISIBLE_OPAQUE : INVISIBLE;
		} else {
			info = new StateInfo(idFor(st), true, opaque);
		}
		states.put(st, info);
		return info;
	}

	private int idFor(BlockState st) {
		Block block = st.getBlock();
		int id = blockIds.getInt(block);
		if (id != 0) return id;
		if (entries.size() >= 65535) throw new IllegalStateException("more than 65535 distinct block types");
		id = entries.size() + 1;
		int flags = 0;
		if (st.isSolidRender()) flags |= Protocol.PAL_OPAQUE;
		if (block.asItem() != Items.AIR) flags |= Protocol.PAL_PLACEABLE;
		if (!st.canOcclude()) flags |= Protocol.PAL_TRANSLUCENT;
		int rgb = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col & 0xFFFFFF;
		entries.add(new Entry(id, block, BuiltInRegistries.BLOCK.getKey(block).toString(), flags, rgb));
		blockIds.put(block, id);
		return id;
	}

	public Entry entry(int id) {
		return id >= 1 && id <= entries.size() ? entries.get(id - 1) : null;
	}

	public Block blockFor(int id) {
		Entry e = entry(id);
		return e == null ? null : e.block;
	}

	public int size() {
		return entries.size();
	}

	/** New GTA session: its palette is empty, so everything must be announced again before use. */
	public void resetAnnouncements() {
		for (Entry e : entries) e.announced = false;
	}

	/** Ensures the PALETTE_ENTRY for {@code id} is queued. Returns false if the ring had no room (retry next tick). */
	public boolean announce(Bridge bridge, int id) {
		Entry e = entry(id);
		if (e == null || e.announced) return true;
		if (!bridge.sendPalette(e.id, e.flags, e.rgb, e.name)) return false;
		e.announced = true;
		return true;
	}
}
