package com.orbitalcannon.passthrough.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Collects the positions of every block change in the mirrored dimension, however it was caused (players, pistons,
 * fluids, falling blocks, explosions, commands, GTA requests). Fed by {@code LevelChunkMixin}, drained by the service
 * each tick. Changes made while a GTA request is being executed are kept apart so the resulting updates can be tagged
 * and sent immediately.
 *
 * <p>The hook runs inside {@code LevelChunk.setBlockState}, so it only does a cheap identity check and one set insert.
 * Only the server thread's changes are recorded.
 */
public final class BlockChangeTracker {
	private static volatile BlockChangeTracker active;

	private final Level level;
	private final Thread serverThread;
	private final LongOpenHashSet normal = new LongOpenHashSet();
	private final LongOpenHashSet fromGta = new LongOpenHashSet();
	private boolean gtaScope;
	private volatile boolean enabled;

	public BlockChangeTracker(Level level, Thread serverThread) {
		this.level = level;
		this.serverThread = serverThread;
	}

	public static void install(BlockChangeTracker tracker) {
		active = tracker;
	}

	public static void uninstall(BlockChangeTracker tracker) {
		if (active == tracker) active = null;
	}

	/** Called from the mixin on every successful {@code LevelChunk.setBlockState}. */
	public static void onBlockChanged(Level level, BlockPos pos) {
		BlockChangeTracker t = active;
		if (t == null || !t.enabled || t.level != level || Thread.currentThread() != t.serverThread) return;
		(t.gtaScope ? t.fromGta : t.normal).add(pos.asLong());
	}

	/** Off while no GTA session is active, so an idle server does not accumulate changes nobody will read. */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void beginGtaScope() {
		gtaScope = true;
	}

	public void endGtaScope() {
		gtaScope = false;
	}

	public LongOpenHashSet normal() {
		return normal;
	}

	public LongOpenHashSet fromGta() {
		return fromGta;
	}
}
