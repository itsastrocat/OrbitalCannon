package com.orbitalcannon.network;

import com.orbitalcannon.registry.ModBlocks;
import com.orbitalcannon.registry.ModItems;
import com.orbitalcannon.strike.OrbitalStrikeManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Packet registration plus the server-side gatekeeping for the targeting flow.
 *
 * <p>Security model: the client can never start a strike on its own. A {@link FireStrikePayload} is only
 * honoured if the server itself previously opened the targeting screen for that player (by the player
 * using a terminal or remote), the uplink that opened it is still valid (player still at the terminal or
 * still holding the remote), and the coordinates pass world-bounds and world-border checks. Each opened
 * screen authorizes at most one shot, and the session expires after {@link #SESSION_TIMEOUT_TICKS}.
 */
public final class OrbitalNetworking {
	/** How long an opened targeting screen stays authorized: 5 minutes. */
	private static final int SESSION_TIMEOUT_TICKS = 5 * 60 * 20;
	/** Extra reach (in blocks) beyond vanilla interaction range allowed between opening the terminal and firing. */
	private static final double TERMINAL_REACH_BUFFER = 4.0;

	private static final Map<UUID, TargetingSession> SESSIONS = new HashMap<>();

	private OrbitalNetworking() {
	}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(OpenTargetingScreenPayload.TYPE, OpenTargetingScreenPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FireStrikePayload.TYPE, FireStrikePayload.CODEC);

		// Fabric runs play payload handlers on the server thread, so touching the world here is safe.
		ServerPlayNetworking.registerGlobalReceiver(FireStrikePayload.TYPE, (payload, context) -> handleFireStrike(context.player(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler.player.getUUID()));
	}

	public static void openTargetingFromTerminal(ServerPlayer player, BlockPos terminalPos) {
		openTargeting(player, new TargetingSession(false, player.level().dimension(), terminalPos.immutable(), expiry(player)));
	}

	public static void openTargetingFromRemote(ServerPlayer player) {
		openTargeting(player, new TargetingSession(true, player.level().dimension(), null, expiry(player)));
	}

	private static long expiry(ServerPlayer player) {
		return player.level().getServer().getTickCount() + SESSION_TIMEOUT_TICKS;
	}

	private static void openTargeting(ServerPlayer player, TargetingSession session) {
		if (!ServerPlayNetworking.canSend(player, OpenTargetingScreenPayload.TYPE)) {
			deny(player, "orbitalcannon.message.client_missing");
			return;
		}

		if (OrbitalStrikeManager.isStriking(player)) {
			deny(player, "orbitalcannon.message.strike_in_progress");
			return;
		}

		SESSIONS.put(player.getUUID(), session);
		ServerPlayNetworking.send(player, new OpenTargetingScreenPayload(session.fromRemote()));
	}

	private static void handleFireStrike(ServerPlayer player, FireStrikePayload payload) {
		// Single use: whatever happens next, this screen can't fire again.
		TargetingSession session = SESSIONS.remove(player.getUUID());
		if (session == null || player.level().getServer().getTickCount() > session.expiresAt()) {
			deny(player, "orbitalcannon.message.no_uplink");
			return;
		}

		if (!session.isStillValid(player)) {
			deny(player, "orbitalcannon.message.uplink_lost");
			return;
		}

		ServerLevel level = player.level();
		BlockPos target = payload.target();
		if (!level.isInWorldBounds(target)) {
			deny(player, "orbitalcannon.message.out_of_bounds", level.getMinY(), level.getMaxY());
			return;
		}

		if (!level.getWorldBorder().isWithinBounds(target)) {
			deny(player, "orbitalcannon.message.outside_border");
			return;
		}

		OrbitalStrikeManager.tryLaunch(player, level, target);
	}

	public static void deny(ServerPlayer player, String translationKey, Object... args) {
		player.sendSystemMessage(Component.translatable(translationKey, args).withStyle(ChatFormatting.RED));
	}

	/**
	 * A server-issued authorization to fire one strike.
	 *
	 * @param terminalPos the terminal that opened the screen, or null when opened from a remote
	 */
	private record TargetingSession(boolean fromRemote, ResourceKey<Level> dimension, @Nullable BlockPos terminalPos, long expiresAt) {
		boolean isStillValid(ServerPlayer player) {
			if (player.isSpectator() || !player.isAlive() || player.level().dimension() != this.dimension) {
				return false;
			}

			if (this.fromRemote) {
				return player.getMainHandItem().is(ModItems.TARGETING_REMOTE) || player.getOffhandItem().is(ModItems.TARGETING_REMOTE);
			}

			BlockPos pos = this.terminalPos;
			Level level = player.level();
			return pos != null
				&& level.isLoaded(pos)
				&& level.getBlockState(pos).is(ModBlocks.ORBITAL_TERMINAL)
				&& player.isWithinBlockInteractionRange(pos, TERMINAL_REACH_BUFFER);
		}
	}
}
