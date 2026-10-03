package com.orbitalcannon.passthrough.world;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

/**
 * Accumulated damage per block position. A bullet does a fraction of what a block can take, so hits build up and the
 * block breaks when the total reaches its threshold; damage that is not followed up heals away after five seconds.
 */
public final class BlockDamageTracker {
	private static final int HEAL_AFTER_TICKS = 100;

	private final Long2FloatOpenHashMap damage = new Long2FloatOpenHashMap();
	private final Long2LongOpenHashMap lastHit = new Long2LongOpenHashMap();

	/** Adds damage and returns the new total. */
	public float add(long pos, float amount, long tick) {
		float total = damage.get(pos) + amount; // missing keys read as 0
		damage.put(pos, total);
		lastHit.put(pos, tick);
		return total;
	}

	public void clear(long pos) {
		damage.remove(pos);
		lastHit.remove(pos);
	}

	public int size() {
		return damage.size();
	}

	/** Drops damage that has not been refreshed recently. Cheap: only scans once a second. */
	public void decay(long tick) {
		if (tick % 20 != 0 || damage.isEmpty()) return;
		for (LongIterator it = damage.keySet().iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			if (tick - lastHit.get(pos) > HEAL_AFTER_TICKS) {
				it.remove();
				lastHit.remove(pos);
			}
		}
	}
}
