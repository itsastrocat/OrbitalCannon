package com.orbitalcannon.passthrough;

import com.orbitalcannon.passthrough.world.ChunkLoader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Deliberately has no client code at all: the mod runs identically on a headless dedicated server and in
 * an integrated server, and never touches rendering, input or windows.
 */
public final class PassthroughMod implements ModInitializer {
	public static final String MOD_ID = "orbitalpassthrough";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static PassthroughService service;
	private static int tickFailures;

	@Override
	public void onInitialize() {
		ChunkLoader.bootstrap(); // registers the chunk ticket type while registries are still open

		ServerLifecycleEvents.SERVER_STARTED.register(PassthroughMod::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		ServerTickEvents.START_SERVER_TICK.register(server -> guarded(() -> {
			if (service != null) service.tickStart();
		}));
		ServerTickEvents.END_SERVER_TICK.register(server -> guarded(() -> {
			if (service != null) service.tickEnd();
		}));
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> PassthroughCommand.register(dispatcher));
		LOGGER.info("Orbital Passthrough loaded");
	}

	private static void start(MinecraftServer server) {
		stop();
		var file = FabricLoader.getInstance().getConfigDir().resolve("orbital-passthrough.properties");
		PassthroughConfig.writeDefaultsIfMissing(file);
		PassthroughConfig cfg = PassthroughConfig.load(file);
		try {
			service = PassthroughService.start(server, cfg);
		} catch (Exception e) {
			LOGGER.error("Orbital Passthrough could not start; the server continues without it", e);
			service = null;
		}
	}

	private static void stop() {
		if (service != null) {
			service.close();
			service = null;
		}
	}

	public static PassthroughService service() {
		return service;
	}

	/** An exception in a tick hook must never take the server down; log it and keep going. */
	private static void guarded(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			if (tickFailures++ < 20) LOGGER.error("Passthrough tick failed", e);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
