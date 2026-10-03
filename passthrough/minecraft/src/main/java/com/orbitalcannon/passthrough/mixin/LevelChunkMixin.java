package com.orbitalcannon.passthrough.mixin;

import com.orbitalcannon.passthrough.world.BlockChangeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric API has no per-block change event, and polling the world would be both slow and late, so this hooks the one
 * method every block change in the game funnels through. {@code setBlockState} returns the previous state, or null
 * when nothing changed, which is exactly the filter needed.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Inject(
		method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
		at = @At("RETURN")
	)
	private void orbitalpassthrough$onSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (cir.getReturnValue() != null) {
			BlockChangeTracker.onBlockChanged(((LevelChunk) (Object) this).getLevel(), pos);
		}
	}
}
