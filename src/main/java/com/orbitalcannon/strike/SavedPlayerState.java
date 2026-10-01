package com.orbitalcannon.strike;

import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Everything needed to put a player back exactly where they were before the strike.
 */
record SavedPlayerState(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot, GameType gameMode, boolean flying) {
	static SavedPlayerState capture(ServerPlayer player) {
		return new SavedPlayerState(
			player.level().dimension(),
			player.position(),
			player.getYRot(),
			player.getXRot(),
			player.gameMode(),
			player.getAbilities().flying
		);
	}

	void restore(ServerPlayer player, MinecraftServer server) {
		ServerLevel level = server.getLevel(this.dimension);
		if (level == null) {
			// The dimension was removed while the strike ran (e.g. a datapack dimension); fall back to the overworld.
			level = server.overworld();
		}

		// Teleport while still in spectator so the player can't take fall damage or suffocate on the way back.
		player.teleportTo(level, this.position.x, this.position.y, this.position.z, Set.of(), this.yRot, this.xRot, true);
		player.setGameMode(this.gameMode);
		if (this.flying && player.getAbilities().mayfly) {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}

		player.resetFallDistance();
	}
}
