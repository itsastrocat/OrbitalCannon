package com.orbitalcannon.passthrough.ipc;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.StructLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every wire struct as a {@link StructLayout}, with natural alignment enforced by the JDK (a misaligned field makes
 * {@code structLayout} throw at class-init time). {@link #fieldOffsets()} flattens them to {@code Struct.field -> offset}
 * so tests can diff against the C++ compiler's {@code offsetof} output, and {@link #verifyConstants()} checks the
 * hand-written constants in {@link Protocol} against the layouts on every startup.
 */
public final class ProtocolLayouts {
	private ProtocolLayouts() {
	}

	public static final StructLayout SHARED_HEADER = structLayout(
		JAVA_INT.withName("magic"), JAVA_INT.withName("version"), JAVA_INT.withName("total_size"), JAVA_INT.withName("init_state"),
		JAVA_INT.withName("ring_a_capacity"), JAVA_INT.withName("ring_b_capacity"), paddingLayout(40),
		JAVA_LONG.withName("mc_epoch"), JAVA_LONG.withName("mc_heartbeat"), paddingLayout(48),
		JAVA_LONG.withName("gta_epoch"), JAVA_LONG.withName("gta_heartbeat"), paddingLayout(48),
		paddingLayout(3904));

	public static final StructLayout RING_CONTROL = structLayout(
		JAVA_LONG.withName("head"), paddingLayout(56),
		JAVA_LONG.withName("tail"), paddingLayout(56),
		JAVA_INT.withName("capacity"), JAVA_INT.withName("mask"), JAVA_LONG.withName("dropped"), JAVA_LONG.withName("records"),
		paddingLayout(104));

	public static final StructLayout MSG_HEADER = structLayout(
		JAVA_SHORT.withName("type"), JAVA_SHORT.withName("flags"), JAVA_INT.withName("len"));

	public static final StructLayout SESSION_START = structLayout(
		JAVA_LONG.withName("mc_epoch"), JAVA_INT.withName("sync_serial"), JAVA_INT.withName("flags"),
		JAVA_DOUBLE.withName("anchor_x"), JAVA_DOUBLE.withName("anchor_y"), JAVA_DOUBLE.withName("anchor_z"),
		JAVA_INT.withName("min_y"), JAVA_INT.withName("max_y"));

	public static final StructLayout PALETTE_ENTRY_HDR = structLayout(
		JAVA_SHORT.withName("id"), JAVA_SHORT.withName("flags"), JAVA_INT.withName("rgb"),
		JAVA_SHORT.withName("name_len"), JAVA_SHORT.withName("reserved"));

	public static final StructLayout SECTION_DATA_HDR = structLayout(
		JAVA_INT.withName("sx"), JAVA_INT.withName("sy"), JAVA_INT.withName("sz"), JAVA_INT.withName("version"),
		JAVA_SHORT.withName("count"), JAVA_SHORT.withName("flags"), JAVA_INT.withName("reserved"));

	public static final StructLayout VOXEL_ENTRY = structLayout(JAVA_SHORT.withName("local"), JAVA_SHORT.withName("id"));

	public static final StructLayout SECTION_UNLOAD = structLayout(
		JAVA_INT.withName("sx"), JAVA_INT.withName("sy"), JAVA_INT.withName("sz"));

	public static final StructLayout VOXEL_UPDATES_HDR = structLayout(
		JAVA_SHORT.withName("count"), JAVA_SHORT.withName("reserved"), JAVA_INT.withName("tick"));

	public static final StructLayout VOXEL_UPDATE = structLayout(
		JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("z"), JAVA_SHORT.withName("id"), JAVA_SHORT.withName("flags"));

	public static final StructLayout ACK = structLayout(
		JAVA_INT.withName("request_seq"), JAVA_SHORT.withName("result"), JAVA_SHORT.withName("reason"),
		JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("z"), JAVA_FLOAT.withName("progress"));

	public static final StructLayout PING = structLayout(JAVA_LONG.withName("token"));

	public static final StructLayout HELLO = structLayout(
		JAVA_LONG.withName("gta_epoch"), JAVA_INT.withName("version"), JAVA_INT.withName("flags"),
		JAVA_FLOAT.withName("view_radius_h"), JAVA_FLOAT.withName("view_radius_v"), JAVA_INT.withName("prop_budget"),
		JAVA_INT.withName("reserved"));

	public static final StructLayout ANCHOR = structLayout(
		JAVA_DOUBLE.withName("gx"), JAVA_DOUBLE.withName("gy"), JAVA_DOUBLE.withName("gz"), JAVA_INT.withName("serial"),
		JAVA_INT.withName("reserved"));

	public static final StructLayout BREAK_REQUEST = structLayout(
		JAVA_INT.withName("seq"), JAVA_BYTE.withName("source"), JAVA_BYTE.withName("face"), JAVA_SHORT.withName("reserved"),
		JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("z"), JAVA_FLOAT.withName("damage"),
		JAVA_INT.withName("weapon_hash"), JAVA_INT.withName("reserved2"));

	public static final StructLayout PLACE_REQUEST = structLayout(
		JAVA_INT.withName("seq"), JAVA_BYTE.withName("face"), JAVA_BYTE.withName("reserved0"), JAVA_SHORT.withName("palette_id"),
		JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("z"), JAVA_INT.withName("reserved1"));

	public static final StructLayout COLLISION_EVENT = structLayout(
		JAVA_BYTE.withName("kind"), JAVA_BYTE.withName("flags"), JAVA_BYTE.withName("nx"), JAVA_BYTE.withName("ny"),
		JAVA_BYTE.withName("nz"), paddingLayout(3), JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("z"),
		JAVA_FLOAT.withName("speed"), JAVA_FLOAT.withName("delta_v"), JAVA_FLOAT.withName("mass"));

	public static final StructLayout PLAYER_STATE = structLayout(
		JAVA_LONG.withName("frame"),
		JAVA_DOUBLE.withName("px"), JAVA_DOUBLE.withName("py"), JAVA_DOUBLE.withName("pz"),
		JAVA_FLOAT.withName("vx"), JAVA_FLOAT.withName("vy"), JAVA_FLOAT.withName("vz"), JAVA_FLOAT.withName("heading"),
		JAVA_DOUBLE.withName("ex"), JAVA_DOUBLE.withName("ey"), JAVA_DOUBLE.withName("ez"),
		JAVA_FLOAT.withName("ax"), JAVA_FLOAT.withName("ay"), JAVA_FLOAT.withName("az"),
		sequenceLayout(3, JAVA_FLOAT).withName("hit_min"), sequenceLayout(3, JAVA_FLOAT).withName("hit_max"),
		JAVA_INT.withName("flags"), JAVA_INT.withName("weapon_hash"), JAVA_INT.withName("vehicle_model"),
		JAVA_FLOAT.withName("vehicle_speed"), JAVA_INT.withName("reserved"));

	public static final StructLayout MC_STATE = structLayout(
		JAVA_LONG.withName("tick"), JAVA_LONG.withName("day_time"), JAVA_FLOAT.withName("mspt"), JAVA_INT.withName("flags"),
		JAVA_INT.withName("sections_streamed"), JAVA_INT.withName("sections_pending"), paddingLayout(16));

	private static final Map<String, StructLayout> ALL = new LinkedHashMap<>();

	static {
		ALL.put("SharedHeader", SHARED_HEADER);
		ALL.put("RingControl", RING_CONTROL);
		ALL.put("MsgHeader", MSG_HEADER);
		ALL.put("SessionStart", SESSION_START);
		ALL.put("PaletteEntryHdr", PALETTE_ENTRY_HDR);
		ALL.put("SectionDataHdr", SECTION_DATA_HDR);
		ALL.put("VoxelEntry", VOXEL_ENTRY);
		ALL.put("SectionUnload", SECTION_UNLOAD);
		ALL.put("VoxelUpdatesHdr", VOXEL_UPDATES_HDR);
		ALL.put("VoxelUpdate", VOXEL_UPDATE);
		ALL.put("Ack", ACK);
		ALL.put("Ping", PING);
		ALL.put("Hello", HELLO);
		ALL.put("Anchor", ANCHOR);
		ALL.put("BreakRequest", BREAK_REQUEST);
		ALL.put("PlaceRequest", PLACE_REQUEST);
		ALL.put("CollisionEvent", COLLISION_EVENT);
		ALL.put("PlayerState", PLAYER_STATE);
		ALL.put("McState", MC_STATE);
	}

	/** {@code "Struct.field" -> byte offset} and {@code "Struct" -> byte size}, in declaration order. */
	public static Map<String, Long> fieldOffsets() {
		Map<String, Long> out = new LinkedHashMap<>();
		for (Map.Entry<String, StructLayout> e : ALL.entrySet()) {
			out.put(e.getKey(), e.getValue().byteSize());
			for (var member : e.getValue().memberLayouts()) {
				member.name().ifPresent(n -> out.put(e.getKey() + "." + n, e.getValue().byteOffset(groupElement(n))));
			}
		}
		return out;
	}

	/** Fails fast if a hand-written constant in {@link Protocol} disagrees with the layout it mirrors. */
	public static void verifyConstants() {
		check("SharedHeader size", SHARED_HEADER.byteSize(), 4096);
		check("RingControl size", RING_CONTROL.byteSize(), 256);
		check("SessionStart size", SESSION_START.byteSize(), Protocol.SS_SIZE);
		check("PaletteEntryHdr size", PALETTE_ENTRY_HDR.byteSize(), Protocol.PE_HDR_SIZE);
		check("SectionDataHdr size", SECTION_DATA_HDR.byteSize(), Protocol.SD_HDR_SIZE);
		check("VoxelEntry size", VOXEL_ENTRY.byteSize(), Protocol.VOXEL_ENTRY_SIZE);
		check("SectionUnload size", SECTION_UNLOAD.byteSize(), Protocol.SU_SIZE);
		check("VoxelUpdatesHdr size", VOXEL_UPDATES_HDR.byteSize(), Protocol.VU_HDR_SIZE);
		check("VoxelUpdate size", VOXEL_UPDATE.byteSize(), Protocol.VU_ENTRY_SIZE);
		check("Ack size", ACK.byteSize(), Protocol.ACK_SIZE);
		check("Hello size", HELLO.byteSize(), Protocol.HELLO_SIZE);
		check("Anchor size", ANCHOR.byteSize(), Protocol.ANCHOR_SIZE);
		check("BreakRequest size", BREAK_REQUEST.byteSize(), Protocol.BR_SIZE);
		check("PlaceRequest size", PLACE_REQUEST.byteSize(), Protocol.PR_SIZE);
		check("CollisionEvent size", COLLISION_EVENT.byteSize(), Protocol.CE_SIZE);
		check("PlayerState size", PLAYER_STATE.byteSize(), Protocol.PS_SIZE);
		check("McState size", MC_STATE.byteSize(), Protocol.MS_SIZE);

		check("SharedHeader.mc_epoch", off(SHARED_HEADER, "mc_epoch"), Protocol.H_MC_EPOCH);
		check("SharedHeader.mc_heartbeat", off(SHARED_HEADER, "mc_heartbeat"), Protocol.H_MC_HEARTBEAT);
		check("SharedHeader.gta_epoch", off(SHARED_HEADER, "gta_epoch"), Protocol.H_GTA_EPOCH);
		check("SharedHeader.gta_heartbeat", off(SHARED_HEADER, "gta_heartbeat"), Protocol.H_GTA_HEARTBEAT);
		check("SharedHeader.init_state", off(SHARED_HEADER, "init_state"), Protocol.H_INIT_STATE);
		check("RingControl.tail", off(RING_CONTROL, "tail"), Protocol.R_TAIL);
		check("RingControl.capacity", off(RING_CONTROL, "capacity"), Protocol.R_CAPACITY);
		check("RingControl.dropped", off(RING_CONTROL, "dropped"), Protocol.R_DROPPED);
		check("SessionStart.min_y", off(SESSION_START, "min_y"), Protocol.SS_MIN_Y);
		check("SectionDataHdr.flags", off(SECTION_DATA_HDR, "flags"), Protocol.SD_FLAGS);
		check("VoxelUpdate.flags", off(VOXEL_UPDATE, "flags"), 14);
		check("Ack.progress", off(ACK, "progress"), Protocol.ACK_PROGRESS);
		check("Hello.prop_budget", off(HELLO, "prop_budget"), Protocol.HELLO_PROP_BUDGET);
		check("Anchor.serial", off(ANCHOR, "serial"), Protocol.ANCHOR_SERIAL);
		check("BreakRequest.damage", off(BREAK_REQUEST, "damage"), Protocol.BR_DAMAGE);
		check("BreakRequest.weapon_hash", off(BREAK_REQUEST, "weapon_hash"), Protocol.BR_WEAPON);
		check("PlaceRequest.palette_id", off(PLACE_REQUEST, "palette_id"), Protocol.PR_PALETTE_ID);
		check("CollisionEvent.delta_v", off(COLLISION_EVENT, "delta_v"), Protocol.CE_DELTA_V);
		check("PlayerState.ex", off(PLAYER_STATE, "ex"), Protocol.PS_EX);
		check("PlayerState.ax", off(PLAYER_STATE, "ax"), Protocol.PS_AX);
		check("PlayerState.hit_min", off(PLAYER_STATE, "hit_min"), Protocol.PS_HIT_MIN);
		check("PlayerState.hit_max", off(PLAYER_STATE, "hit_max"), Protocol.PS_HIT_MAX);
		check("PlayerState.flags", off(PLAYER_STATE, "flags"), Protocol.PS_FLAGS);
		check("PlayerState.vehicle_speed", off(PLAYER_STATE, "vehicle_speed"), Protocol.PS_VEHICLE_SPEED);
		check("McState.mspt", off(MC_STATE, "mspt"), Protocol.MS_MSPT);
		check("McState.sections_pending", off(MC_STATE, "sections_pending"), Protocol.MS_SECTIONS_PENDING);

		check("kTotalSize", Protocol.RING_B_OFF + Protocol.RING_B_CAP, Protocol.TOTAL_SIZE);
	}

	private static long off(StructLayout s, String field) {
		return s.byteOffset(groupElement(field));
	}

	private static void check(String what, long actual, long expected) {
		if (actual != expected) {
			throw new IllegalStateException("Protocol constant mismatch: " + what + " layout=" + actual + " constant=" + expected);
		}
	}
}
