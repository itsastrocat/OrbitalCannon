package com.orbitalcannon.item;

import com.orbitalcannon.network.OrbitalNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Handheld uplink to the orbital cannon. Using it asks the server to open the targeting screen.
 */
public class TargetingRemoteItem extends Item {
	public TargetingRemoteItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer) {
			OrbitalNetworking.openTargetingFromRemote(serverPlayer);
		}

		return InteractionResult.SUCCESS;
	}
}
