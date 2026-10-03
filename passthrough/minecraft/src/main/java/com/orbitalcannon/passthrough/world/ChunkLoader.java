package com.orbitalcannon.passthrough.world;

import com.orbitalcannon.passthrough.PassthroughMod;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps the chunks around the GTA player loaded (and the ones right next to them simulating) even though no real
 * player exists. Tickets are not persisted, so a crash cannot leave forced chunks behind in the world save.
 *
 * <p>Radius semantics: a ticket of radius 0 holds its chunk fully loaded; radius 2 makes the centre entity-ticking and
 * its neighbours block-ticking, which is what falling sand, fluids and redstone need near the player.
 */
public final class ChunkLoader {
	private static final TicketType TICKET = Registry.register(
		BuiltInRegistries.TICKET_TYPE,
		PassthroughMod.id("stream"),
		new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE)
	);
	private static final int SIM_RADIUS = 2;

	private final LongOpenHashSet loaded = new LongOpenHashSet();
	private long simChunk = Long.MIN_VALUE;
	private ServerLevel level;

	/** Forces the static registration to happen during mod initialisation, while registries are still open. */
	public static void bootstrap() {
		// Touching the class runs its static initialiser, which registers the ticket type.
	}

	/** Brings the set of held tickets in line with {@code desiredColumns}, with the simulation hub on the player's chunk. */
	public void update(ServerLevel level, LongSet desiredColumns, int playerChunkX, int playerChunkZ) {
		this.level = level;
		var cache = level.getChunkSource();
		for (LongIterator it = desiredColumns.iterator(); it.hasNext(); ) {
			long c = it.nextLong();
			if (loaded.add(c)) cache.addTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(c), ChunkPos.getZ(c)), 0);
		}
		for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
			long c = it.nextLong();
			if (!desiredColumns.contains(c)) {
				cache.removeTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(c), ChunkPos.getZ(c)), 0);
				it.remove();
			}
		}
		long hub = ChunkPos.pack(playerChunkX, playerChunkZ);
		if (hub != simChunk) {
			if (simChunk != Long.MIN_VALUE) {
				cache.removeTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(simChunk), ChunkPos.getZ(simChunk)), SIM_RADIUS);
			}
			cache.addTicketWithRadius(TICKET, new ChunkPos(playerChunkX, playerChunkZ), SIM_RADIUS);
			simChunk = hub;
		}
	}

	/** Holds a single chunk loaded (used to make sure the anchor point is available before the session starts). */
	public void hold(ServerLevel level, int chunkX, int chunkZ) {
		this.level = level;
		long c = ChunkPos.pack(chunkX, chunkZ);
		if (loaded.add(c)) level.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(chunkX, chunkZ), 0);
	}

	/** Releases everything this loader holds. */
	public void releaseAll() {
		if (level == null) return;
		var cache = level.getChunkSource();
		for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
			long c = it.nextLong();
			cache.removeTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(c), ChunkPos.getZ(c)), 0);
		}
		loaded.clear();
		if (simChunk != Long.MIN_VALUE) {
			cache.removeTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(simChunk), ChunkPos.getZ(simChunk)), SIM_RADIUS);
			simChunk = Long.MIN_VALUE;
		}
	}

	public int heldColumns() {
		return loaded.size();
	}
}
