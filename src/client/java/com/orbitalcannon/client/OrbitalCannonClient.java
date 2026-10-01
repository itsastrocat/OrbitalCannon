package com.orbitalcannon.client;

import com.orbitalcannon.client.screen.TargetingScreen;
import com.orbitalcannon.network.OpenTargetingScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class OrbitalCannonClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Handlers run on the client thread, so opening a screen here is safe.
		ClientPlayNetworking.registerGlobalReceiver(
			OpenTargetingScreenPayload.TYPE,
			(payload, context) -> context.client().gui.setScreen(TargetingScreen.create(context.client(), payload.fromRemote()))
		);
	}
}
