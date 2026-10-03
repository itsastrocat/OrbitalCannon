package com.orbitalcannon.passthrough.mixin;

import com.orbitalcannon.passthrough.PassthroughService;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dedicated server with no players stops simulating after {@code pause-when-empty-seconds}. While a GTA client is
 * attached the passthrough <i>is</i> the player, so the world must keep ticking (falling sand, flowing water,
 * redstone). This answers "never pause" without touching the user's {@code server.properties}.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
	@Inject(method = "pauseWhenEmptySeconds()I", at = @At("HEAD"), cancellable = true)
	private void orbitalpassthrough$neverPauseWhileAttached(CallbackInfoReturnable<Integer> cir) {
		PassthroughService.mixinCalls.incrementAndGet();
		if (PassthroughService.keepAwake()) {
			PassthroughService.mixinOverrides.incrementAndGet();
			cir.setReturnValue(0); // <= 0 disables the pause
		}
	}
}
