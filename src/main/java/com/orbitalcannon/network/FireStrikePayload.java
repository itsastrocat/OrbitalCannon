package com.orbitalcannon.network;

import com.orbitalcannon.OrbitalCannon;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: the player pressed FIRE with the given target coordinates.
 * The server treats every field as untrusted input; see {@link OrbitalNetworking#handleFireStrike}.
 */
public record FireStrikePayload(int x, int y, int z) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<FireStrikePayload> TYPE = new CustomPacketPayload.Type<>(OrbitalCannon.id("fire_strike"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FireStrikePayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, FireStrikePayload::x,
		ByteBufCodecs.VAR_INT, FireStrikePayload::y,
		ByteBufCodecs.VAR_INT, FireStrikePayload::z,
		FireStrikePayload::new
	);

	public BlockPos target() {
		return new BlockPos(this.x, this.y, this.z);
	}

	@Override
	public CustomPacketPayload.Type<FireStrikePayload> type() {
		return TYPE;
	}
}
