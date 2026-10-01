package com.orbitalcannon.strike;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Shapes vanilla explosions into a sphere.
 *
 * <ul>
 *   <li>Inside the sphere, every breakable block has zero blast resistance, so a charge's reach is the same
 *       whether it's in dirt, stone or obsidian. That makes {@link DetonationPlan}'s coverage maths hold.</li>
 *   <li>Outside the sphere, every position (including air) has effectively infinite resistance, so blast rays
 *       stop dead at the sphere wall and nothing outside is touched. This is what gives the crater its shape.</li>
 *   <li>Unbreakable blocks (bedrock, barriers, end portal frames...) keep their vanilla resistance and never break.</li>
 *   <li>Armed orbital charges aren't knocked around by their neighbours' blasts, so they stay put until they fire.</li>
 * </ul>
 */
public final class SphereExplosionCalculator extends ExplosionDamageCalculator {
	/** Same resistance as bedrock: stops any ray in one step. */
	private static final float CONTAINMENT_RESISTANCE = 3_600_000.0F;

	private final Vec3 center;
	private final double radiusSqr;

	public SphereExplosionCalculator(Vec3 center, double radius) {
		this.center = center;
		this.radiusSqr = radius * radius;
	}

	public boolean contains(BlockPos pos) {
		return this.center.distanceToSqr(Vec3.atCenterOf(pos)) <= this.radiusSqr;
	}

	@Override
	public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter level, BlockPos pos, BlockState block, FluidState fluid) {
		if (!this.contains(pos)) {
			return Optional.of(CONTAINMENT_RESISTANCE);
		}

		if (block.isAir() && fluid.isEmpty()) {
			return Optional.empty();
		}

		if (isIndestructible(block, level, pos)) {
			return super.getBlockExplosionResistance(explosion, level, pos, block, fluid);
		}

		return Optional.of(0.0F);
	}

	@Override
	public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
		return this.contains(pos) && !isIndestructible(state, level, pos);
	}

	@Override
	public float getKnockbackMultiplier(Entity entity) {
		return entity instanceof PrimedTnt tnt && tnt.entityTags().contains(OrbitalStrike.CHARGE_TAG) ? 0.0F : 1.0F;
	}

	static boolean isIndestructible(BlockState state, BlockGetter level, BlockPos pos) {
		return state.getDestroySpeed(level, pos) < 0.0F;
	}
}
