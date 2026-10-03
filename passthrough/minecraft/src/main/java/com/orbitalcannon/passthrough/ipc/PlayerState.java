package com.orbitalcannon.passthrough.ipc;

import static com.orbitalcannon.passthrough.ipc.Protocol.*;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * The GTA player's per-frame state, in GTA world coordinates. One reusable instance is refilled from the seqlock slot
 * every tick, so reading it allocates nothing.
 */
public final class PlayerState {
	public long frame;
	public double px, py, pz; // feet position
	public float vx, vy, vz;
	public float heading;
	public double ex, ey, ez; // camera / aim ray origin
	public float ax, ay, az; // aim direction (unit)
	public final float[] hitMin = new float[3]; // hitbox corner offsets relative to (px,py,pz), GTA axes
	public final float[] hitMax = new float[3];
	public int flags;
	public int weaponHash;
	public int vehicleModel;
	public float vehicleSpeed;

	public boolean onFoot() {
		return (flags & PF_ON_FOOT) != 0;
	}

	public boolean inVehicle() {
		return (flags & PF_IN_VEHICLE) != 0;
	}

	public boolean dead() {
		return (flags & PF_DEAD) != 0;
	}

	/** Seqlock reader for the {@code SeqSlot<PlayerState>} the GTA side writes every frame. */
	public static final class Reader {
		private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle(); // (segment, byteOffset)

		private final MemorySegment seg;
		private final long slot;
		private final MemorySegment scratch;

		public Reader(MemorySegment seg, long slotOff) {
			this.seg = seg;
			this.slot = slotOff;
			this.scratch = Arena.ofAuto().allocate(PS_SIZE, 8);
		}

		/**
		 * Copies a consistent snapshot into {@code out}. Returns false if the GTA side never wrote one or a stable read
		 * could not be obtained (the caller just keeps the previous values and tries again next tick).
		 */
		public boolean read(PlayerState out) {
			for (int spin = 0; spin < 1000; spin++) {
				int s1 = (int) INT.getAcquire(seg, slot);
				if (s1 == 0) return false;
				if ((s1 & 1) != 0) {
					Thread.onSpinWait();
					continue; // writer is mid-update
				}
				MemorySegment.copy(seg, slot + SLOT_DATA, scratch, 0, PS_SIZE);
				VarHandle.acquireFence();
				if ((int) INT.getAcquire(seg, slot) != s1) continue; // torn: retry
				decode(scratch, out);
				return true;
			}
			return false;
		}

		private static void decode(MemorySegment s, PlayerState o) {
			o.frame = s.get(JAVA_LONG_UNALIGNED, PS_FRAME);
			o.px = s.get(JAVA_DOUBLE_UNALIGNED, PS_PX);
			o.py = s.get(JAVA_DOUBLE_UNALIGNED, PS_PY);
			o.pz = s.get(JAVA_DOUBLE_UNALIGNED, PS_PZ);
			o.vx = s.get(JAVA_FLOAT_UNALIGNED, PS_VX);
			o.vy = s.get(JAVA_FLOAT_UNALIGNED, PS_VY);
			o.vz = s.get(JAVA_FLOAT_UNALIGNED, PS_VZ);
			o.heading = s.get(JAVA_FLOAT_UNALIGNED, PS_HEADING);
			o.ex = s.get(JAVA_DOUBLE_UNALIGNED, PS_EX);
			o.ey = s.get(JAVA_DOUBLE_UNALIGNED, PS_EY);
			o.ez = s.get(JAVA_DOUBLE_UNALIGNED, PS_EZ);
			o.ax = s.get(JAVA_FLOAT_UNALIGNED, PS_AX);
			o.ay = s.get(JAVA_FLOAT_UNALIGNED, PS_AY);
			o.az = s.get(JAVA_FLOAT_UNALIGNED, PS_AZ);
			for (int i = 0; i < 3; i++) {
				o.hitMin[i] = s.get(JAVA_FLOAT_UNALIGNED, PS_HIT_MIN + 4L * i);
				o.hitMax[i] = s.get(JAVA_FLOAT_UNALIGNED, PS_HIT_MAX + 4L * i);
			}
			o.flags = s.get(JAVA_INT_UNALIGNED, PS_FLAGS);
			o.weaponHash = s.get(JAVA_INT_UNALIGNED, PS_WEAPON);
			o.vehicleModel = s.get(JAVA_INT_UNALIGNED, PS_VEHICLE_MODEL);
			o.vehicleSpeed = s.get(JAVA_FLOAT_UNALIGNED, PS_VEHICLE_SPEED);
		}
	}

	/** Seqlock writer, used by tests that play the GTA role. */
	public static final class Writer {
		private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
		private final MemorySegment seg;
		private final long slot;

		public Writer(MemorySegment seg, long slotOff) {
			this.seg = seg;
			this.slot = slotOff;
		}

		public void write(PlayerState s) {
			int seq = (int) INT.getAcquire(seg, slot);
			INT.setRelease(seg, slot, seq + 1); // odd: write in progress
			VarHandle.releaseFence();
			long b = slot + SLOT_DATA;
			seg.set(JAVA_LONG_UNALIGNED, b + PS_FRAME, s.frame);
			seg.set(JAVA_DOUBLE_UNALIGNED, b + PS_PX, s.px);
			seg.set(JAVA_DOUBLE_UNALIGNED, b + PS_PY, s.py);
			seg.set(JAVA_DOUBLE_UNALIGNED, b + PS_PZ, s.pz);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_VX, s.vx);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_VY, s.vy);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_VZ, s.vz);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_HEADING, s.heading);
			seg.set(JAVA_DOUBLE_UNALIGNED, b + PS_EX, s.ex);
			seg.set(JAVA_DOUBLE_UNALIGNED, b + PS_EY, s.ey);
			seg.set(JAVA_DOUBLE_UNALIGNED, b + PS_EZ, s.ez);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_AX, s.ax);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_AY, s.ay);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_AZ, s.az);
			for (int i = 0; i < 3; i++) {
				seg.set(JAVA_FLOAT_UNALIGNED, b + PS_HIT_MIN + 4L * i, s.hitMin[i]);
				seg.set(JAVA_FLOAT_UNALIGNED, b + PS_HIT_MAX + 4L * i, s.hitMax[i]);
			}
			seg.set(JAVA_INT_UNALIGNED, b + PS_FLAGS, s.flags);
			seg.set(JAVA_INT_UNALIGNED, b + PS_WEAPON, s.weaponHash);
			seg.set(JAVA_INT_UNALIGNED, b + PS_VEHICLE_MODEL, s.vehicleModel);
			seg.set(JAVA_FLOAT_UNALIGNED, b + PS_VEHICLE_SPEED, s.vehicleSpeed);
			INT.setRelease(seg, slot, seq + 2); // even: stable
		}
	}
}
