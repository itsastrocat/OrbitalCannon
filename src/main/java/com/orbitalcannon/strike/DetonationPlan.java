package com.orbitalcannon.strike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;

/**
 * Controlled-detonation schedule that hollows out a sphere with a bounded number of TNT charges.
 *
 * <h2>Why not one TNT per block?</h2>
 * A radius-15 sphere holds ~14,000 blocks. Spawning a primed TNT for each would bring any server to its
 * knees. Instead we place a small number of charges (~100 for radius 15) so that every block of the sphere
 * is within the guaranteed blast reach of at least one charge, and detonate them in a fixed budget per tick.
 *
 * <h2>Geometry</h2>
 * Charges sit on concentric spherical shells around the target. Shell radii start one block inside the
 * sphere wall and step inward by {@link #SHELL_GAP}, plus a single charge at the centre. On each shell the
 * charges are spread evenly with a Fibonacci (golden-angle) lattice, using as many points as needed to keep
 * neighbouring charges about {@link #CHARGE_SPACING} blocks apart: {@code n = ceil(4 * pi * r^2 / spacing^2)}.
 *
 * <h2>Coverage guarantee</h2>
 * Charges explode through {@link SphereExplosionCalculator}, which gives every breakable block inside the
 * sphere zero blast resistance. A vanilla explosion ray then loses 0.315 power per 0.3-block step
 * (1.05 per block) and starts with at least {@code 0.7 * power}, so a charge always clears at least
 * {@code 0.7 * 7 / 1.05 = 4.67} blocks in every direction. The farthest any block can be from its nearest
 * charge is roughly {@code sqrt((SHELL_GAP / 2)^2 + (CHARGE_SPACING / sqrt(3))^2) = sqrt(2.5^2 + 3.46^2) = 4.27}
 * blocks, which is inside that reach.
 *
 * <h2>Timing</h2>
 * Shells go off from the inside out, so each wave blasts into the cavity left by the one before. Within a shell
 * the charges fire in lattice order (a top-to-bottom spiral sweep) at {@link #DETONATIONS_PER_TICK} per tick,
 * with {@link #SHELL_PAUSE_TICKS} between shells. Each charge is armed (a real primed TNT entity appears)
 * {@link #ARM_LEAD_TICKS} before it fires, so at most {@code DETONATIONS_PER_TICK} entities spawn per tick.
 * For radius 15 that's 105 charges over about 3.6 seconds.
 */
public final class DetonationPlan {
	/** Explosion power of a single charge (vanilla TNT is 4). */
	public static final float CHARGE_POWER = 7.0F;
	/** Radial distance between consecutive shells of charges. */
	static final double SHELL_GAP = 5.0;
	/** Target distance between neighbouring charges on the same shell. */
	static final double CHARGE_SPACING = 6.0;
	/** How far inside the sphere wall the outermost shell sits. */
	static final double OUTER_SHELL_INSET = 1.0;
	/** Hard cap on explosions (and TNT spawns) per server tick. */
	static final int DETONATIONS_PER_TICK = 4;
	/** Quiet ticks between the end of one shell and the start of the next. */
	static final int SHELL_PAUSE_TICKS = 8;
	/** How many ticks a charge's TNT entity is visible before it detonates. */
	static final int ARM_LEAD_TICKS = 20;

	private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

	private final List<Charge> charges;
	private final int lastDetonationTick;

	private DetonationPlan(List<Charge> charges) {
		this.charges = Collections.unmodifiableList(charges);
		this.lastDetonationTick = charges.isEmpty() ? 0 : charges.getLast().detonateTick();
	}

	/**
	 * Builds the plan for a sphere.
	 *
	 * @param center    the centre of the sphere in world coordinates
	 * @param radius    the sphere radius in blocks
	 * @param placeable filters out charge positions that can't hold a charge (outside the world's build height)
	 */
	public static DetonationPlan create(Vec3 center, double radius, Predicate<Vec3> placeable) {
		List<Double> shellRadii = new ArrayList<>();
		for (double r = radius - OUTER_SHELL_INSET; r > 0.0; r -= SHELL_GAP) {
			shellRadii.add(r);
		}
		shellRadii.add(0.0);
		Collections.reverse(shellRadii);

		List<Charge> charges = new ArrayList<>();
		int cursor = ARM_LEAD_TICKS;
		for (int shell = 0; shell < shellRadii.size(); shell++) {
			List<Vec3> points = shellPoints(center, shellRadii.get(shell), shell);
			int placed = 0;
			for (Vec3 point : points) {
				if (!placeable.test(point)) {
					continue;
				}

				int detonateTick = cursor + placed / DETONATIONS_PER_TICK;
				charges.add(new Charge(point, detonateTick - ARM_LEAD_TICKS, detonateTick));
				placed++;
			}

			if (placed > 0) {
				cursor += (placed - 1) / DETONATIONS_PER_TICK + 1 + SHELL_PAUSE_TICKS;
			}
		}

		return new DetonationPlan(charges);
	}

	/** Evenly spread points on one shell, ordered from the top of the sphere to the bottom. */
	private static List<Vec3> shellPoints(Vec3 center, double r, int shellIndex) {
		if (r <= 0.0) {
			return List.of(center);
		}

		int count = Math.max(4, (int) Math.ceil(4.0 * Math.PI * r * r / (CHARGE_SPACING * CHARGE_SPACING)));
		// Rotate each shell differently so charges on neighbouring shells don't line up radially.
		double phase = shellIndex * GOLDEN_ANGLE * 0.5;
		List<Vec3> points = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double y = 1.0 - 2.0 * (i + 0.5) / count;
			double ringRadius = Math.sqrt(1.0 - y * y);
			double theta = GOLDEN_ANGLE * i + phase;
			points.add(center.add(Math.cos(theta) * ringRadius * r, y * r, Math.sin(theta) * ringRadius * r));
		}

		return points;
	}

	/** Charges in firing order. Both arm and detonate ticks are non-decreasing across the list. */
	public List<Charge> charges() {
		return this.charges;
	}

	public int lastDetonationTick() {
		return this.lastDetonationTick;
	}

	/**
	 * @param position     where the charge goes off
	 * @param armTick      tick (relative to the start of the annihilation phase) at which its TNT entity spawns
	 * @param detonateTick tick at which it explodes
	 */
	public record Charge(Vec3 position, int armTick, int detonateTick) {
	}
}
