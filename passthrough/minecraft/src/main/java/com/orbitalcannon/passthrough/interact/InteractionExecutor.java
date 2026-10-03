package com.orbitalcannon.passthrough.interact;

import static com.orbitalcannon.passthrough.ipc.Protocol.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import com.orbitalcannon.passthrough.PassthroughConfig;
import com.orbitalcannon.passthrough.ipc.Bridge;
import com.orbitalcannon.passthrough.ipc.PlayerState;
import com.orbitalcannon.passthrough.world.BlockChangeTracker;
import com.orbitalcannon.passthrough.world.BlockDamageTracker;
import com.orbitalcannon.passthrough.world.PaletteRegistry;
import com.orbitalcannon.passthrough.world.WorldTransform;
import java.lang.foreign.MemorySegment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Executes what the GTA player does: breaks blocks (bullets, tools, explosives), places blocks, and cracks blocks that
 * vehicles smash into, all as authoritative changes to the Minecraft world.
 *
 * <p>GTA is the client, so nothing it sends is trusted: every request is rate limited and range checked against the
 * player's last reported position, coordinates are bounds checked, and unbreakable blocks stay unbreakable. A refused
 * request still gets an {@code ACK} naming the reason. Runs on the server thread only.
 */
public final class InteractionExecutor {
	private final ServerLevel level;
	private final Bridge bridge;
	private final WorldTransform tf;
	private final PaletteRegistry palette;
	private final PassthroughConfig cfg;
	private final BlockChangeTracker tracker;
	private final BlockDamageTracker damage = new BlockDamageTracker();

	private long tick;
	private int requestsThisTick;

	public long breaks, places, denied, collisionBreaks;

	public InteractionExecutor(ServerLevel level, Bridge bridge, WorldTransform tf, PaletteRegistry palette, PassthroughConfig cfg,
		BlockChangeTracker tracker) {
		this.level = level;
		this.bridge = bridge;
		this.tf = tf;
		this.palette = palette;
		this.cfg = cfg;
		this.tracker = tracker;
	}

	public void beginTick(long tick) {
		this.tick = tick;
		requestsThisTick = 0;
		damage.decay(tick);
	}

	// ---- BREAK -----------------------------------------------------------------------------------------------------

	public void onBreak(MemorySegment seg, long p, PlayerState ps, boolean playerKnown) {
		int seq = seg.get(JAVA_INT_UNALIGNED, p + BR_SEQ);
		int source = seg.get(JAVA_BYTE, p + BR_SOURCE) & 0xFF;
		int x = seg.get(JAVA_INT_UNALIGNED, p + BR_X), y = seg.get(JAVA_INT_UNALIGNED, p + BR_Y), z = seg.get(JAVA_INT_UNALIGNED, p + BR_Z);
		float dmg = seg.get(JAVA_FLOAT_UNALIGNED, p + BR_DAMAGE);

		int refusal = precheck(x, y, z, reachFor(source), ps, playerKnown);
		if (refusal >= 0) {
			deny(seq, refusal, x, y, z);
			return;
		}
		if (!(dmg > 0f) || Float.isInfinite(dmg)) { // also rejects NaN
			deny(seq, ACK_DENIED_RATE, x, y, z);
			return;
		}
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			deny(seq, ACK_DENIED_AIR, x, y, z);
			return;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0f) {
			deny(seq, ACK_DENIED_UNBREAKABLE, x, y, z);
			return;
		}

		tracker.beginGtaScope();
		try {
			if (source == SRC_EXPLOSIVE) {
				explode(seq, pos, dmg);
			} else {
				hit(seq, pos, state, hardness, dmg);
			}
		} finally {
			tracker.endGtaScope();
		}
	}

	private void hit(int seq, BlockPos pos, BlockState state, float hardness, float dmg) {
		float threshold = Math.max(hardness, 0.05f) * cfg.damagePerHardness;
		float total = damage.add(pos.asLong(), dmg, tick);
		if (total >= threshold) {
			damage.clear(pos.asLong());
			level.destroyBlock(pos, cfg.dropItems);
			breaks++;
			bridge.sendAck(seq, ACK_BROKEN, pos.getX(), pos.getY(), pos.getZ(), 1f);
		} else {
			bridge.sendAck(seq, ACK_DAMAGED, pos.getX(), pos.getY(), pos.getZ(), total / threshold);
		}
	}

	/** Spherical blast: every block whose hardness the falling-off energy can overcome is destroyed, up to a cap. */
	private void explode(int seq, BlockPos centre, float energy) {
		float r = cfg.explosiveRadius;
		int ri = (int) Math.ceil(r);
		int destroyed = 0;
		boolean centreBroken = false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -ri; dx <= ri && destroyed < cfg.explosiveMaxBlocks; dx++) {
			for (int dy = -ri; dy <= ri && destroyed < cfg.explosiveMaxBlocks; dy++) {
				for (int dz = -ri; dz <= ri && destroyed < cfg.explosiveMaxBlocks; dz++) {
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 > r * r) continue;
					m.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
					if (level.isOutsideBuildHeight(m) || !loaded(m)) continue;
					BlockState st = level.getBlockState(m);
					if (st.isAir()) continue;
					float hardness = st.getDestroySpeed(level, m);
					if (hardness < 0f) continue;
					float effective = energy * (1f - (float) (d2 / (r * r)));
					if (effective < Math.max(hardness, 0.05f) * cfg.damagePerHardness) continue;
					level.destroyBlock(m, cfg.dropItems);
					damage.clear(m.asLong());
					destroyed++;
					if (dx == 0 && dy == 0 && dz == 0) centreBroken = true;
				}
			}
		}
		breaks += destroyed;
		bridge.sendAck(seq, centreBroken ? ACK_BROKEN : ACK_DAMAGED, centre.getX(), centre.getY(), centre.getZ(), centreBroken ? 1f : 0f);
	}

	// ---- PLACE -----------------------------------------------------------------------------------------------------

	public void onPlace(MemorySegment seg, long p, PlayerState ps, boolean playerKnown) {
		int seq = seg.get(JAVA_INT_UNALIGNED, p + PR_SEQ);
		int face = seg.get(JAVA_BYTE, p + PR_FACE) & 0xFF;
		int paletteId = seg.get(JAVA_SHORT_UNALIGNED, p + PR_PALETTE_ID) & 0xFFFF;
		int cx = seg.get(JAVA_INT_UNALIGNED, p + PR_X), cy = seg.get(JAVA_INT_UNALIGNED, p + PR_Y), cz = seg.get(JAVA_INT_UNALIGNED, p + PR_Z);
		if (face > FACE_EAST) face = FACE_UP;
		int x = cx + FACE_NORMAL[face][0], y = cy + FACE_NORMAL[face][1], z = cz + FACE_NORMAL[face][2];

		int refusal = precheck(x, y, z, cfg.reachTool, ps, playerKnown);
		if (refusal >= 0) {
			deny(seq, refusal, x, y, z);
			return;
		}
		Block block = palette.blockFor(paletteId);
		if (block == null) {
			deny(seq, ACK_DENIED_BAD_PALETTE, x, y, z);
			return;
		}
		BlockPos pos = new BlockPos(x, y, z);
		if (!level.getBlockState(pos).canBeReplaced()) {
			deny(seq, ACK_DENIED_OCCUPIED, x, y, z);
			return;
		}
		BlockState placed = block.defaultBlockState();
		if (!placed.canSurvive(level, pos)) {
			deny(seq, ACK_DENIED_OCCUPIED, x, y, z);
			return;
		}
		// Never entomb the player: refuse a solid block that would overlap their (GTA-reported) hitbox.
		if (playerKnown && !placed.getCollisionShape(level, pos).isEmpty() && playerBox(ps).deflate(0.05).intersects(new AABB(pos))) {
			deny(seq, ACK_DENIED_HITBOX, x, y, z);
			return;
		}
		tracker.beginGtaScope();
		try {
			level.setBlock(pos, placed, Block.UPDATE_ALL);
		} finally {
			tracker.endGtaScope();
		}
		places++;
		bridge.sendAck(seq, ACK_PLACED, x, y, z, 1f);
	}

	// ---- COLLISION -------------------------------------------------------------------------------------------------

	public void onCollision(MemorySegment seg, long p, PlayerState ps, boolean playerKnown) {
		int kind = seg.get(JAVA_BYTE, p + CE_KIND) & 0xFF;
		if (kind == COL_PED && !cfg.pedImpactsBreak) return;
		int nx = seg.get(JAVA_BYTE, p + CE_NX), ny = seg.get(JAVA_BYTE, p + CE_NY), nz = seg.get(JAVA_BYTE, p + CE_NZ);
		int x = seg.get(JAVA_INT_UNALIGNED, p + CE_X), y = seg.get(JAVA_INT_UNALIGNED, p + CE_Y), z = seg.get(JAVA_INT_UNALIGNED, p + CE_Z);
		float deltaV = seg.get(JAVA_FLOAT_UNALIGNED, p + CE_DELTA_V);
		float mass = seg.get(JAVA_FLOAT_UNALIGNED, p + CE_MASS);
		if (!(deltaV > 0f) || !(mass > 0f) || deltaV > 200f || mass > 100_000f) return; // also rejects NaN
		if (precheck(x, y, z, cfg.reachVehicle, ps, playerKnown) >= 0) return;
		nx = Integer.signum(nx);
		ny = Integer.signum(ny);
		nz = Integer.signum(nz);

		double energy = 0.5 * mass * deltaV * deltaV; // joules
		tracker.beginGtaScope();
		try {
			// The car carries on into the wall: spend energy on the contact block, then the ones behind it.
			for (int i = 0; i < cfg.vehicleMaxDepth; i++) {
				BlockPos pos = new BlockPos(x - nx * i, y - ny * i, z - nz * i);
				if (level.isOutsideBuildHeight(pos) || !loaded(pos)) break;
				BlockState st = level.getBlockState(pos);
				if (st.isAir()) continue;
				float hardness = st.getDestroySpeed(level, pos);
				if (hardness < 0f) break;
				double need = Math.max(hardness, 0.05f) * cfg.vehicleEnergyPerHardness;
				if (energy < need) break;
				energy -= need;
				level.destroyBlock(pos, cfg.dropItems);
				collisionBreaks++;
			}
		} finally {
			tracker.endGtaScope();
		}
	}

	// ---- shared checks ---------------------------------------------------------------------------------------------

	private double reachFor(int source) {
		return switch (source) {
			case SRC_BULLET -> cfg.reachBullet;
			case SRC_EXPLOSIVE -> cfg.reachExplosive;
			case SRC_MELEE -> cfg.reachMelee;
			case SRC_VEHICLE -> cfg.reachVehicle;
			default -> cfg.reachTool;
		};
	}

	/** Returns -1 if the request may proceed, otherwise the ACK result code explaining the refusal. */
	private int precheck(int x, int y, int z, double reach, PlayerState ps, boolean playerKnown) {
		if (!tf.valid) return ACK_DENIED_NO_ANCHOR;
		if (++requestsThisTick > cfg.maxRequestsPerTick) return ACK_DENIED_RATE;
		BlockPos pos = new BlockPos(x, y, z);
		if (level.isOutsideBuildHeight(pos) || !level.isInWorldBounds(pos)) return ACK_DENIED_HEIGHT;
		if (!loaded(pos)) return ACK_DENIED_UNLOADED;
		if (!playerKnown) return ACK_DENIED_REACH; // cannot verify range without the player's position: fail closed
		double ex = tf.mcX(ps.px), ey = tf.mcY(ps.pz) + cfg.playerEyeHeight, ez = tf.mcZ(ps.py);
		double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
		if (dx * dx + dy * dy + dz * dz > reach * reach) return ACK_DENIED_REACH;
		return -1;
	}

	private boolean loaded(BlockPos pos) {
		return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** The GTA hitbox (axis-aligned, GTA axes, relative to the feet) as a Minecraft-space box. */
	private AABB playerBox(PlayerState ps) {
		double ax = tf.mcX(ps.px + ps.hitMin[0]), bx = tf.mcX(ps.px + ps.hitMax[0]);
		double ay = tf.mcY(ps.pz + ps.hitMin[2]), by = tf.mcY(ps.pz + ps.hitMax[2]);
		// GTA +Y (north) is Minecraft -Z, so the two GTA Y bounds swap order when mapped.
		double az = tf.mcZ(ps.py + ps.hitMax[1]), bz = tf.mcZ(ps.py + ps.hitMin[1]);
		return new AABB(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
	}

	private void deny(int seq, int reason, int x, int y, int z) {
		denied++;
		bridge.sendAck(seq, reason, x, y, z, 0f);
	}
}
