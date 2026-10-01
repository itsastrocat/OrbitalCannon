package com.orbitalcannon.strike;

import com.orbitalcannon.OrbitalCannon;
import com.orbitalcannon.network.OrbitalNetworking;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;

/**
 * Owns every running strike, ticks them at the end of each server tick, and makes sure no player is ever left
 * stranded in spectator mode (disconnects and server shutdowns restore them immediately).
 */
public final class OrbitalStrikeManager {
	/** Per-player cooldown between strikes, counted from the moment one is fired: 10 seconds. */
	private static final int COOLDOWN_TICKS = 200;
	/** Server-wide cap on simultaneous strikes, so several players can't stack the load. */
	private static final int MAX_CONCURRENT_STRIKES = 3;

	/** Chunk ticket that keeps the blast zone loaded and simulated while a strike runs. */
	static final TicketType STRIKE_TICKET = Registry.register(
		BuiltInRegistries.TICKET_TYPE,
		OrbitalCannon.id("orbital_strike"),
		new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE)
	);

	private static final List<OrbitalStrike> ACTIVE = new ArrayList<>();
	private static final Map<UUID, Integer> COOLDOWN_UNTIL = new HashMap<>();

	private OrbitalStrikeManager() {
	}

	public static void initialize() {
		ServerTickEvents.END_SERVER_TICK.register(OrbitalStrikeManager::tick);

		// Fabric fires DISCONNECT before the player's data is saved, so restoring here means they log back in
		// at their original spot in their original game mode, not floating in the sky as a spectator.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			for (OrbitalStrike strike : ACTIVE) {
				if (strike.ownerId().equals(handler.player.getUUID())) {
					strike.restorePlayer(handler.player, server);
				}
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (OrbitalStrike strike : ACTIVE) {
				strike.abort(server);
			}

			ACTIVE.clear();
			COOLDOWN_UNTIL.clear();
		});
	}

	public static boolean isStriking(ServerPlayer player) {
		for (OrbitalStrike strike : ACTIVE) {
			if (strike.ownerId().equals(player.getUUID()) && strike.phase() != OrbitalStrike.Phase.DONE) {
				return true;
			}
		}

		return false;
	}

	/** Starts a strike if the player is allowed to fire one right now. Coordinates must already be validated. */
	public static void tryLaunch(ServerPlayer player, ServerLevel level, BlockPos target) {
		MinecraftServer server = level.getServer();
		if (isStriking(player)) {
			OrbitalNetworking.deny(player, "orbitalcannon.message.strike_in_progress");
			return;
		}

		int now = server.getTickCount();
		int readyAt = COOLDOWN_UNTIL.getOrDefault(player.getUUID(), 0);
		if (now < readyAt) {
			OrbitalNetworking.deny(player, "orbitalcannon.message.cooldown", (readyAt - now + 19) / 20);
			return;
		}

		if (ACTIVE.size() >= MAX_CONCURRENT_STRIKES) {
			OrbitalNetworking.deny(player, "orbitalcannon.message.cannon_busy");
			return;
		}

		OrbitalStrike strike = new OrbitalStrike(player, level, target);
		strike.start(player);
		ACTIVE.add(strike);
		COOLDOWN_UNTIL.put(player.getUUID(), now + COOLDOWN_TICKS);
		OrbitalCannon.LOGGER.info(
			"{} called an orbital strike on {} in {}", player.getName().getString(), target.toShortString(), level.dimension().identifier()
		);
	}

	private static void tick(MinecraftServer server) {
		Iterator<OrbitalStrike> iterator = ACTIVE.iterator();
		while (iterator.hasNext()) {
			OrbitalStrike strike = iterator.next();
			try {
				if (strike.tick(server)) {
					iterator.remove();
				}
			} catch (RuntimeException e) {
				// Never let a broken strike crash the server or strand its player in spectator mode.
				OrbitalCannon.LOGGER.error("Orbital strike failed, aborting", e);
				strike.abort(server);
				iterator.remove();
			}
		}

		if (!COOLDOWN_UNTIL.isEmpty()) {
			int now = server.getTickCount();
			COOLDOWN_UNTIL.values().removeIf(readyAt -> readyAt <= now);
		}
	}
}
