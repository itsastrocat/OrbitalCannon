package com.orbitalcannon.passthrough.ipc;

/**
 * Java mirror of {@code passthrough/protocol/orbital_ipc.h}, the single source of truth for the shared-memory wire
 * format. Offsets are written out as constants so hot paths compile down to fixed-offset loads and stores;
 * {@link ProtocolLayouts} declares the same structs as {@code StructLayout}s and a test compares every field offset
 * against the C++ compiler's own {@code offsetof} output, so the two sides cannot drift apart silently.
 *
 * <p>x86-64 little endian only. Every field is naturally aligned.
 */
public final class Protocol {
	private Protocol() {
	}

	public static final int MAGIC = 0x5450434F; // bytes 'O','C','P','T'
	public static final int VERSION = 1;
	public static final String DEFAULT_NAME = "OrbitalCannonPassthrough_v1";

	// ---- region map -----------------------------------------------------------------------------------------------
	public static final long HEADER_OFF = 0x0000;
	public static final long GTA_STATE_OFF = 0x1000; // seqlock slot: PlayerState, GTA -> MC
	public static final long MC_STATE_OFF = 0x2000; // seqlock slot: McState, MC -> GTA
	public static final long RING_A_CTL_OFF = 0x3000; // MC -> GTA
	public static final long RING_B_CTL_OFF = 0x4000; // GTA -> MC
	public static final long RING_A_OFF = 0x5000;
	public static final long RING_A_CAP = 4L << 20;
	public static final long RING_B_OFF = RING_A_OFF + RING_A_CAP;
	public static final long RING_B_CAP = 512L << 10;
	public static final long TOTAL_SIZE = RING_B_OFF + RING_B_CAP;

	// ---- SharedHeader ---------------------------------------------------------------------------------------------
	public static final long H_MAGIC = HEADER_OFF;
	public static final long H_VERSION = HEADER_OFF + 4;
	public static final long H_TOTAL_SIZE = HEADER_OFF + 8;
	public static final long H_INIT_STATE = HEADER_OFF + 12;
	public static final long H_RING_A_CAP = HEADER_OFF + 16;
	public static final long H_RING_B_CAP = HEADER_OFF + 20;
	public static final long H_MC_EPOCH = HEADER_OFF + 64;
	public static final long H_MC_HEARTBEAT = HEADER_OFF + 72;
	public static final long H_GTA_EPOCH = HEADER_OFF + 128;
	public static final long H_GTA_HEARTBEAT = HEADER_OFF + 136;

	public static final int INIT_RAW = 0;
	public static final int INIT_BUSY = 1;
	public static final int INIT_READY = 2;

	// ---- RingControl (offsets relative to the control block) ------------------------------------------------------
	public static final long R_HEAD = 0;
	public static final long R_TAIL = 64;
	public static final long R_CAPACITY = 128;
	public static final long R_MASK = 132;
	public static final long R_DROPPED = 136;
	public static final long R_RECORDS = 144;

	// ---- record framing -------------------------------------------------------------------------------------------
	public static final int MSG_HEADER_SIZE = 8; // u16 type, u16 flags, u32 len
	public static final int MAX_PAYLOAD = 16384;

	public static int align8(int n) {
		return (n + 7) & ~7;
	}

	public static int recordSize(int payloadLen) {
		return MSG_HEADER_SIZE + align8(payloadLen);
	}

	// ---- message types --------------------------------------------------------------------------------------------
	public static final int MSG_PAD = 0;
	public static final int MSG_PING = 7;
	public static final int MSG_PONG = 8;
	// ring A: Minecraft -> GTA
	public static final int MSG_SESSION_START = 1;
	public static final int MSG_PALETTE_ENTRY = 2;
	public static final int MSG_SECTION_DATA = 3;
	public static final int MSG_SECTION_UNLOAD = 4;
	public static final int MSG_VOXEL_UPDATES = 5;
	public static final int MSG_ACK = 6;
	// ring B: GTA -> Minecraft
	public static final int MSG_HELLO = 16;
	public static final int MSG_ANCHOR = 17;
	public static final int MSG_BREAK_REQUEST = 18;
	public static final int MSG_PLACE_REQUEST = 19;
	public static final int MSG_COLLISION = 20;

	// ---- SessionStart (48) ----------------------------------------------------------------------------------------
	public static final int SS_SIZE = 48;
	public static final int SS_MC_EPOCH = 0, SS_SYNC_SERIAL = 8, SS_FLAGS = 12, SS_ANCHOR_X = 16, SS_ANCHOR_Y = 24,
		SS_ANCHOR_Z = 32, SS_MIN_Y = 40, SS_MAX_Y = 44;

	// ---- PaletteEntryHdr (12) + name bytes ------------------------------------------------------------------------
	public static final int PE_HDR_SIZE = 12;
	public static final int PE_ID = 0, PE_FLAGS = 2, PE_RGB = 4, PE_NAME_LEN = 8;
	public static final int PAL_OPAQUE = 1, PAL_PLACEABLE = 2, PAL_TRANSLUCENT = 4;

	// ---- SectionDataHdr (24) + VoxelEntry[count] (4 each) ---------------------------------------------------------
	public static final int SD_HDR_SIZE = 24;
	public static final int SD_SX = 0, SD_SY = 4, SD_SZ = 8, SD_VERSION = 12, SD_COUNT = 16, SD_FLAGS = 18;
	public static final int SEC_REPLACE = 1, SEC_LAST = 2;
	public static final int VOXEL_ENTRY_SIZE = 4; // u16 local (x | z<<4 | y<<8), u16 id
	public static final int MAX_ENTRIES_PER_SECTION_MSG = (MAX_PAYLOAD - SD_HDR_SIZE) / VOXEL_ENTRY_SIZE;

	// ---- SectionUnload (12) ---------------------------------------------------------------------------------------
	public static final int SU_SIZE = 12;

	// ---- VoxelUpdatesHdr (8) + VoxelUpdate[count] (16 each) -------------------------------------------------------
	public static final int VU_HDR_SIZE = 8;
	public static final int VU_COUNT = 0, VU_TICK = 4;
	public static final int VU_ENTRY_SIZE = 16; // i32 x,y,z; u16 id; u16 flags
	public static final int UPD_FROM_GTA = 1;
	public static final int MAX_UPDATES_PER_MSG = (MAX_PAYLOAD - VU_HDR_SIZE) / VU_ENTRY_SIZE;

	// ---- Ack (24) -------------------------------------------------------------------------------------------------
	public static final int ACK_SIZE = 24;
	public static final int ACK_SEQ = 0, ACK_RESULT = 4, ACK_REASON = 6, ACK_X = 8, ACK_Y = 12, ACK_Z = 16, ACK_PROGRESS = 20;

	public static final int ACK_BROKEN = 0, ACK_DAMAGED = 1, ACK_PLACED = 2, ACK_DENIED_REACH = 3, ACK_DENIED_UNLOADED = 4,
		ACK_DENIED_UNBREAKABLE = 5, ACK_DENIED_OCCUPIED = 6, ACK_DENIED_AIR = 7, ACK_DENIED_HITBOX = 8, ACK_DENIED_RATE = 9,
		ACK_DENIED_HEIGHT = 10, ACK_DENIED_NO_ANCHOR = 11, ACK_DENIED_BAD_PALETTE = 12;

	// ---- Ping/Pong (8) --------------------------------------------------------------------------------------------
	public static final int PING_SIZE = 8;

	// ---- Hello (32) -----------------------------------------------------------------------------------------------
	public static final int HELLO_SIZE = 32;
	public static final int HELLO_GTA_EPOCH = 0, HELLO_VERSION = 8, HELLO_FLAGS = 12, HELLO_VIEW_H = 16, HELLO_VIEW_V = 20,
		HELLO_PROP_BUDGET = 24;

	// ---- Anchor (32) ----------------------------------------------------------------------------------------------
	public static final int ANCHOR_SIZE = 32;
	public static final int ANCHOR_GX = 0, ANCHOR_GY = 8, ANCHOR_GZ = 16, ANCHOR_SERIAL = 24;

	// ---- BreakRequest (32) ----------------------------------------------------------------------------------------
	public static final int BR_SIZE = 32;
	public static final int BR_SEQ = 0, BR_SOURCE = 4, BR_FACE = 5, BR_X = 8, BR_Y = 12, BR_Z = 16, BR_DAMAGE = 20, BR_WEAPON = 24;
	public static final int SRC_BULLET = 1, SRC_MELEE = 2, SRC_EXPLOSIVE = 3, SRC_TOOL = 4, SRC_VEHICLE = 5;

	// ---- PlaceRequest (24) ----------------------------------------------------------------------------------------
	public static final int PR_SIZE = 24;
	public static final int PR_SEQ = 0, PR_FACE = 4, PR_PALETTE_ID = 6, PR_X = 8, PR_Y = 12, PR_Z = 16;

	// ---- CollisionEvent (32) --------------------------------------------------------------------------------------
	public static final int CE_SIZE = 32;
	public static final int CE_KIND = 0, CE_FLAGS = 1, CE_NX = 2, CE_NY = 3, CE_NZ = 4, CE_X = 8, CE_Y = 12, CE_Z = 16,
		CE_SPEED = 20, CE_DELTA_V = 24, CE_MASS = 28;
	public static final int COL_PED = 1, COL_VEHICLE = 2;

	// ---- faces: Minecraft Direction.get3DDataValue() order --------------------------------------------------------
	public static final int FACE_DOWN = 0, FACE_UP = 1, FACE_NORTH = 2, FACE_SOUTH = 3, FACE_WEST = 4, FACE_EAST = 5;
	public static final int[][] FACE_NORMAL = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	// ---- PlayerState seqlock slot (GTA -> MC): seq at +0, payload at +64, 128 bytes -------------------------------
	public static final long SLOT_DATA = 64;
	public static final int PS_SIZE = 128;
	public static final int PS_FRAME = 0, PS_PX = 8, PS_PY = 16, PS_PZ = 24, PS_VX = 32, PS_VY = 36, PS_VZ = 40, PS_HEADING = 44,
		PS_EX = 48, PS_EY = 56, PS_EZ = 64, PS_AX = 72, PS_AY = 76, PS_AZ = 80, PS_HIT_MIN = 84, PS_HIT_MAX = 96, PS_FLAGS = 108,
		PS_WEAPON = 112, PS_VEHICLE_MODEL = 116, PS_VEHICLE_SPEED = 120;
	public static final int PF_ON_FOOT = 1, PF_IN_VEHICLE = 2, PF_SHOOTING = 4, PF_DEAD = 8, PF_RAGDOLL = 16, PF_ANCHORED = 32;

	// ---- McState seqlock slot (MC -> GTA): payload at +64, 48 bytes -----------------------------------------------
	public static final int MS_SIZE = 48;
	public static final int MS_TICK = 0, MS_DAY_TIME = 8, MS_MSPT = 16, MS_FLAGS = 20, MS_SECTIONS_STREAMED = 24, MS_SECTIONS_PENDING = 28;
}
