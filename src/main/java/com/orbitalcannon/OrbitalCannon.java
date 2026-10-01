package com.orbitalcannon;

import com.orbitalcannon.network.OrbitalNetworking;
import com.orbitalcannon.registry.ModBlocks;
import com.orbitalcannon.registry.ModItems;
import com.orbitalcannon.strike.OrbitalStrikeManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrbitalCannon implements ModInitializer {
	public static final String MOD_ID = "orbitalcannon";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModBlocks.initialize();
		ModItems.initialize();
		OrbitalNetworking.initialize();
		OrbitalStrikeManager.initialize();

		LOGGER.info("Orbital Strike Cannon online. Satellite uplink established.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
