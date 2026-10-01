package com.orbitalcannon.network;

import com.orbitalcannon.OrbitalCannon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C: the server has authorized a targeting session and asks the client to open the targeting screen.
 *
 * @param fromRemote true when opened by the handheld Targeting Remote (the client then pre-fills the
 *                   coordinates with the block the player is looking at), false for the Orbital Terminal
 */
public record OpenTargetingScreenPayload(boolean fromRemote) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<OpenTargetingScreenPayload> TYPE = new CustomPacketPayload.Type<>(OrbitalCannon.id("open_targeting_screen"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenTargetingScreenPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, OpenTargetingScreenPayload::fromRemote,
		OpenTargetingScreenPayload::new
	);

	@Override
	public CustomPacketPayload.Type<OpenTargetingScreenPayload> type() {
		return TYPE;
	}
}
