package com.orbitalcannon.strike;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One orbital strike, driven tick by tick by {@link OrbitalStrikeManager}.
 *
 * <ol>
 *   <li><b>Target lock</b> ({@link #start}): save the player's position, dimension and game mode, switch them to
 *       spectator and park them {@link #CAMERA_HEIGHT} blocks above the target looking straight down. A beam of
 *       particles starts descending from the sky and a siren sounds.</li>
 *   <li><b>Delay</b> ({@link Phase#TARGET_LOCK}): {@link #LOCK_DELAY_TICKS} ticks of countdown while the beam reaches
 *       the ground.</li>
 *   <li><b>Annihilation</b> ({@link Phase#ANNIHILATION}, {@link Phase#SWEEP}): run the {@link DetonationPlan}, arming
 *       and detonating real primed TNT a few at a time, then sweep up any stray blocks the blast rays missed.</li>
 *   <li><b>Return</b> ({@link Phase#RETURN}): after the dust settles, put the player back and restore their game mode.</li>
 * </ol>
 */
final class OrbitalStrike {
	/** Scoreboard-style tag on our TNT entities, so they can be told apart from ordinary TNT. */
	static final String CHARGE_TAG = "orbitalcannon_charge";

	/** Blast sphere radius: diameter 30. */
	static final double BLAST_RADIUS = 15.0;
	/** How far above the target the spectator camera is parked. */
	static final int CAMERA_HEIGHT = 60;
	/** Countdown between target lock and the first detonation: 4 seconds. */
	static final int LOCK_DELAY_TICKS = 80;
	/** Pause after the last detonation before the clean-up sweep, letting falling blocks and fluids react. */
	static final int SETTLE_TICKS = 10;
	/** Block positions checked per tick by the clean-up sweep (the sphere's bounding cube is 31^3 = 29,791). */
	static final int SWEEP_POSITIONS_PER_TICK = 8192;
	/** How long the player keeps watching after the last blast before being sent home: 3 seconds. */
	static final int RETURN_DELAY_TICKS = 60;
	/**
	 * Destroy item drops inside the blast zone. ~14,000 destroyed blocks would otherwise leave hundreds of item
	 * entities lying around, which is exactly the kind of lag this mod is trying to avoid.
	 */
	static final boolean VAPORIZE_DEBRIS = true;
	/** Extra fuse on armed charges so they can never go off on their own before the controller fires them. */
	private static final int FUSE_SAFETY_TICKS = 40;

	private static final DustParticleOptions RED_DUST = new DustParticleOptions(0xFF2A1A, 2.0F);
	private static final DustParticleOptions TARGET_DUST = new DustParticleOptions(0xFF0000, 1.5F);

	enum Phase {
		TARGET_LOCK,
		ANNIHILATION,
		SWEEP,
		RETURN,
		DONE
	}

	private final UUID ownerId;
	private final ServerLevel level;
	private final BlockPos target;
	private final Vec3 center;
	private final double skyY;
	private final SphereExplosionCalculator calculator;
	private final DetonationPlan plan;
	private final PrimedTnt[] armedCharges;
	private final ChunkPos ticketPos;

	private @Nullable SavedPlayerState savedState;
	private boolean playerRestored;
	private Phase phase = Phase.TARGET_LOCK;
	private int phaseAge;
	private int armCursor;
	private int detonateCursor;
	private int sweepCursor;

	OrbitalStrike(ServerPlayer owner, ServerLevel level, BlockPos target) {
		this.ownerId = owner.getUUID();
		this.level = level;
		this.target = target.immutable();
		this.center = Vec3.atCenterOf(this.target);
		this.skyY = this.target.getY() + CAMERA_HEIGHT;
		this.calculator = new SphereExplosionCalculator(this.center, BLAST_RADIUS);
		this.plan = DetonationPlan.create(this.center, BLAST_RADIUS, point -> level.isInWorldBounds(BlockPos.containing(point)));
		this.armedCharges = new PrimedTnt[this.plan.charges().size()];
		this.ticketPos = ChunkPos.containing(this.target);
	}

	UUID ownerId() {
		return this.ownerId;
	}

	Phase phase() {
		return this.phase;
	}

	// ------------------------------------------------------------------------------------------------
	// Phase 1: target lock & aerial view
	// ------------------------------------------------------------------------------------------------

	void start(ServerPlayer player) {
		this.savedState = SavedPlayerState.capture(player);

		// Keep the strike area loaded and ticking even if the player's view distance doesn't cover it.
		this.level.getChunkSource().addTicketWithRadius(OrbitalStrikeManager.STRIKE_TICKET, this.ticketPos, 2);

		player.setGameMode(GameType.SPECTATOR);
		player.teleportTo(this.level, this.center.x, this.skyY, this.center.z, Set.of(), player.getYRot(), 90.0F, true);

		this.playToOwner(player, SoundEvents.BEACON_ACTIVATE, 1.0F, 0.6F);
		this.playSiren();
		player.sendSystemMessage(
			Component.translatable("orbitalcannon.message.target_locked", this.target.getX(), this.target.getY(), this.target.getZ())
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
		);
	}

	// ------------------------------------------------------------------------------------------------
	// Tick driver
	// ------------------------------------------------------------------------------------------------

	/** Advances the strike by one tick. Returns true once it's completely finished. */
	boolean tick(MinecraftServer server) {
		ServerPlayer owner = this.playerRestored ? null : server.getPlayerList().getPlayer(this.ownerId);
		switch (this.phase) {
			case TARGET_LOCK -> this.tickTargetLock(owner);
			case ANNIHILATION -> this.tickAnnihilation(owner);
			case SWEEP -> this.tickSweep();
			case RETURN -> this.tickReturn(server, owner);
			case DONE -> {
			}
		}

		this.phaseAge++;
		return this.phase == Phase.DONE;
	}

	private void enterPhase(Phase next) {
		this.phase = next;
		this.phaseAge = -1; // incremented to 0 at the end of this tick
	}

	// ------------------------------------------------------------------------------------------------
	// Phase 2: delay / countdown
	// ------------------------------------------------------------------------------------------------

	private void tickTargetLock(@Nullable ServerPlayer owner) {
		int t = this.phaseAge;
		double progress = Math.min(1.0, (t + 1) / (double) LOCK_DELAY_TICKS);
		// The beam's leading edge descends from the camera height to the target over the countdown.
		double headY = this.skyY - (this.skyY - this.center.y) * progress;
		this.spawnBeam(headY, false);
		this.spawnSparks(headY);
		if (t % 5 == 0) {
			this.spawnTargetRing();
		}

		if (t == LOCK_DELAY_TICKS / 2) {
			this.playSiren();
		}

		// Targeting beeps speed up as impact approaches.
		int beepInterval = t < LOCK_DELAY_TICKS / 2 ? 10 : t < LOCK_DELAY_TICKS * 3 / 4 ? 5 : 2;
		if (t % beepInterval == 0) {
			this.playAt(this.center, SoundEvents.NOTE_BLOCK_BIT, 3.0F, 1.0F + (float) progress);
		}

		if (t == LOCK_DELAY_TICKS - 34) {
			this.playAt(this.center, SoundEvents.WARDEN_SONIC_CHARGE, 6.0F, 0.7F);
		}

		if (owner != null && t % 20 == 0) {
			int secondsLeft = (LOCK_DELAY_TICKS - t + 19) / 20;
			owner.sendOverlayMessage(Component.translatable("orbitalcannon.message.countdown", secondsLeft).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}

		if (t + 1 >= LOCK_DELAY_TICKS) {
			this.enterPhase(Phase.ANNIHILATION);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Phase 3: annihilation sphere
	// ------------------------------------------------------------------------------------------------

	private void tickAnnihilation(@Nullable ServerPlayer owner) {
		int t = this.phaseAge;
		if (t == 0) {
			this.impactFlash(owner);
		}

		List<DetonationPlan.Charge> charges = this.plan.charges();
		while (this.armCursor < charges.size() && charges.get(this.armCursor).armTick() <= t) {
			this.armCharge(this.armCursor, owner);
			this.armCursor++;
		}

		boolean detonatedThisTick = false;
		while (this.detonateCursor < charges.size() && charges.get(this.detonateCursor).detonateTick() <= t) {
			this.detonateCharge(this.detonateCursor);
			this.detonateCursor++;
			detonatedThisTick = true;
		}

		// Vanilla only sends explosion packets to players within 64 blocks, and the spectator camera can be further
		// than that from the bottom of the sphere. Give them one big boom per tick so they hear every wave.
		if (detonatedThisTick && owner != null) {
			this.playToOwner(owner, SoundEvents.GENERIC_EXPLODE.value(), 4.0F, 0.6F + this.level.getRandom().nextFloat() * 0.3F);
		}

		if (t <= this.plan.lastDetonationTick()) {
			this.spawnBeam(this.center.y, true);
		}

		if (t % 10 == 0) {
			this.vaporizeDebris();
		}

		if (this.detonateCursor >= charges.size() && t >= this.plan.lastDetonationTick() + SETTLE_TICKS) {
			this.enterPhase(Phase.SWEEP);
		}
	}

	private void armCharge(int index, @Nullable ServerPlayer owner) {
		DetonationPlan.Charge charge = this.plan.charges().get(index);
		Vec3 pos = charge.position();
		PrimedTnt tnt = new PrimedTnt(this.level, pos.x, pos.y - 0.5, pos.z, owner);
		tnt.setFuse(charge.detonateTick() - charge.armTick() + FUSE_SAFETY_TICKS);
		tnt.setNoGravity(true);
		tnt.setDeltaMovement(Vec3.ZERO);
		tnt.addTag(CHARGE_TAG);
		if (this.level.addFreshEntity(tnt)) {
			this.armedCharges[index] = tnt;
		}
	}

	private void detonateCharge(int index) {
		Vec3 pos = this.plan.charges().get(index).position();
		PrimedTnt tnt = this.armedCharges[index];
		this.armedCharges[index] = null;
		Entity source = tnt != null && !tnt.isRemoved() ? tnt : null;

		// The TNT entity is the explosion's source, so kills are credited to the player who called the strike.
		this.level.explode(
			source,
			Explosion.getDefaultDamageSource(this.level, source),
			this.calculator,
			pos.x,
			pos.y,
			pos.z,
			DetonationPlan.CHARGE_POWER,
			false,
			Level.ExplosionInteraction.TNT
		);
		if (tnt != null) {
			tnt.discard();
		}

		// Long-range copy of the blast visual for the spectator camera (see the sound note in tickAnnihilation).
		this.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
	}

	/** Clean-up pass: removes the few blocks that slipped between blast rays, so the crater is a true sphere. */
	private void tickSweep() {
		int r = (int) Math.ceil(BLAST_RADIUS);
		int side = 2 * r + 1;
		int total = side * side * side;
		int end = Math.min(total, this.sweepCursor + SWEEP_POSITIONS_PER_TICK);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = this.sweepCursor; i < end; i++) {
			// Walk top-down so blocks above are removed before the ones holding them up.
			int dy = r - i / (side * side);
			int dx = (i / side) % side - r;
			int dz = i % side - r;
			pos.set(this.target.getX() + dx, this.target.getY() + dy, this.target.getZ() + dz);
			if (!this.calculator.contains(pos) || !this.level.isInWorldBounds(pos)) {
				continue;
			}

			BlockState state = this.level.getBlockState(pos);
			if (!state.isAir() && !SphereExplosionCalculator.isIndestructible(state, this.level, pos)) {
				this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}

		this.sweepCursor = end;
		if (this.sweepCursor >= total) {
			this.vaporizeDebris();
			this.enterPhase(Phase.RETURN);
		}
	}

	private void vaporizeDebris() {
		if (!VAPORIZE_DEBRIS) {
			return;
		}

		double reach = BLAST_RADIUS + 2.0;
		AABB box = AABB.ofSize(this.center, reach * 2.0, reach * 2.0, reach * 2.0);
		for (ItemEntity item : this.level.getEntitiesOfClass(ItemEntity.class, box, item -> item.distanceToSqr(this.center) <= reach * reach)) {
			item.discard();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Phase 4: return
	// ------------------------------------------------------------------------------------------------

	private void tickReturn(MinecraftServer server, @Nullable ServerPlayer owner) {
		if (this.phaseAge % 10 == 0) {
			this.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, this.center.x, this.center.y, this.center.z, 20, BLAST_RADIUS * 0.6, 2.0, BLAST_RADIUS * 0.6, 0.0, 0.02, 0.0);
		}

		if (this.phaseAge >= RETURN_DELAY_TICKS) {
			if (owner != null) {
				this.restorePlayer(owner, server);
				owner.sendSystemMessage(Component.translatable("orbitalcannon.message.strike_complete").withStyle(ChatFormatting.GREEN));
			}

			this.finish();
		}
	}

	/** Puts the owner back where they were. Safe to call more than once; only the first call does anything. */
	void restorePlayer(ServerPlayer player, MinecraftServer server) {
		if (this.playerRestored || this.savedState == null) {
			return;
		}

		this.playerRestored = true;
		this.savedState.restore(player, server);
	}

	/** Immediately ends the strike: used when the server shuts down. */
	void abort(MinecraftServer server) {
		ServerPlayer owner = server.getPlayerList().getPlayer(this.ownerId);
		if (owner != null) {
			this.restorePlayer(owner, server);
		}

		for (int i = 0; i < this.armedCharges.length; i++) {
			if (this.armedCharges[i] != null) {
				this.armedCharges[i].discard();
				this.armedCharges[i] = null;
			}
		}

		this.finish();
	}

	private void finish() {
		if (this.phase != Phase.DONE) {
			this.level.getChunkSource().removeTicketWithRadius(OrbitalStrikeManager.STRIKE_TICKET, this.ticketPos, 2);
			this.phase = Phase.DONE;
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Effects
	// ------------------------------------------------------------------------------------------------

	/** Vertical particle column from the sky down to {@code bottomY}. */
	private void spawnBeam(double bottomY, boolean firing) {
		double height = this.skyY - bottomY;
		if (height <= 0.0) {
			return;
		}

		int density = (int) Math.ceil(height / (firing ? 2.0 : 4.0));
		// ALTERNATIVE randomization spreads particles uniformly over [y, y + height], i.e. along the whole column.
		this.sendParticlesUniform(ParticleTypes.END_ROD, bottomY, height, density, -0.35);
		this.sendParticlesUniform(RED_DUST, bottomY, height, density, 0.0);
		if (firing) {
			this.sendParticlesUniform(ParticleTypes.ELECTRIC_SPARK, bottomY, height, density / 2, -0.8);
		}
	}

	private void spawnSparks(double y) {
		this.sendParticles(ParticleTypes.ELECTRIC_SPARK, this.center.x, y, this.center.z, 8, 0.4, 0.4, 0.4, 0.2, 0.2, 0.2);
		this.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.center.x, y, this.center.z, 3, 0.2, 0.2, 0.2, 0.05, 0.05, 0.05);
	}

	/** A red ring on the ground marking the edge of the blast zone. */
	private void spawnTargetRing() {
		int points = 48;
		double y = this.target.getY() + 1.1;
		for (int i = 0; i < points; i++) {
			double angle = (Math.PI * 2.0 * i) / points;
			this.sendParticles(
				TARGET_DUST, this.center.x + Math.cos(angle) * BLAST_RADIUS, y, this.center.z + Math.sin(angle) * BLAST_RADIUS, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
			);
		}
	}

	private void impactFlash(@Nullable ServerPlayer owner) {
		for (double y = this.center.y; y < this.skyY; y += 4.0) {
			this.sendParticles(ParticleTypes.SONIC_BOOM, this.center.x, y, this.center.z, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
		}

		this.playAt(this.center, SoundEvents.WARDEN_SONIC_BOOM, 8.0F, 0.5F);
		this.playAt(this.center, SoundEvents.LIGHTNING_BOLT_THUNDER, 16.0F, 0.8F);
		if (owner != null) {
			this.playToOwner(owner, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0F, 0.6F);
			owner.sendOverlayMessage(Component.translatable("orbitalcannon.message.impact").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		}
	}

	/** Raid horn, audible up to 256 blocks away (sound range is 16 blocks per unit of volume). */
	private void playSiren() {
		this.level.playSound(null, this.center.x, this.center.y, this.center.z, SoundEvents.RAID_HORN, SoundSource.HOSTILE, 16.0F, 1.0F);
	}

	private void playAt(Vec3 pos, SoundEvent sound, float volume, float pitch) {
		this.level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.HOSTILE, volume, pitch);
	}

	private void playAt(Vec3 pos, Holder<SoundEvent> sound, float volume, float pitch) {
		this.level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.HOSTILE, volume, pitch);
	}

	/** Plays a sound only for the strike owner, right at their camera. */
	private void playToOwner(ServerPlayer owner, SoundEvent sound, float volume, float pitch) {
		owner.connection.send(
			new ClientboundSoundPacket(
				BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
				SoundSource.HOSTILE,
				owner.getX(),
				owner.getEyeY(),
				owner.getZ(),
				volume,
				pitch,
				this.level.getRandom().nextLong()
			)
		);
	}

	/** Forced particles: visible up to 512 blocks away, regardless of the viewer's particle setting. */
	private void sendParticles(ParticleOptions particle, double x, double y, double z, int count, double dx, double dy, double dz, double sx, double sy, double sz) {
		this.level.sendParticles(particle, true, true, x, y, z, count, dx, dy, dz, sx, sy, sz, ClientboundLevelParticlesPacket.RandomizationType.DEFAULT);
	}

	private void sendParticlesUniform(ParticleOptions particle, double bottomY, double height, int count, double ySpeed) {
		if (count <= 0) {
			return;
		}

		this.level.sendParticles(
			particle, true, true, this.center.x, bottomY, this.center.z, count, 0.0, height, 0.0, 0.0, ySpeed, 0.0, ClientboundLevelParticlesPacket.RandomizationType.ALTERNATIVE
		);
	}
}
