// Orbital Passthrough IPC protocol, version 1.
//
// Single source of truth for the shared-memory wire format between the Minecraft (Java/Fabric) process and the
// GTA V (ScriptHookV) process. The Java mirror lives in
//   minecraft/src/main/java/com/orbitalcannon/passthrough/ipc/Protocol.java
// and tools/layout_dump + LayoutParityTest verify that both sides agree on every offset.
//
// Design rules
//   * x86-64 little endian only. Every field is naturally aligned. All structs are standard-layout PODs.
//   * Two lock-free single-producer/single-consumer byte rings (one per direction) carry ordered, lossless
//     event streams. Two seqlock slots carry "latest value wins" state that must never queue up.
//   * Cursors are free-running 64-bit byte counters (never wrapped), published with release stores and observed
//     with acquire loads. On x86-64 that compiles to plain MOVs: no locked instructions, no syscalls.
//   * Head and tail live on separate cache lines so the producer and consumer never false-share.
//   * Everything read from the mapping is untrusted input (another process owns the other half). Readers
//     bounds-check every record and resynchronise instead of trusting lengths.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <type_traits>

#if defined(__BYTE_ORDER__)
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "orbital_ipc requires a little-endian target");
#endif
static_assert(sizeof(void*) == 8, "orbital_ipc requires a 64-bit target");

namespace ocp {

// ---------------------------------------------------------------------------------------------------------------
// Region map
// ---------------------------------------------------------------------------------------------------------------
inline constexpr uint32_t kMagic = 0x5450434Fu;  // bytes 'O','C','P','T'
inline constexpr uint32_t kVersion = 1;

inline constexpr size_t kHeaderOff = 0x0000;    // SharedHeader           (4 KiB page)
inline constexpr size_t kGtaStateOff = 0x1000;  // SeqSlot<PlayerState>   (4 KiB page)  GTA -> MC, latest wins
inline constexpr size_t kMcStateOff = 0x2000;   // SeqSlot<McState>       (4 KiB page)  MC -> GTA, latest wins
inline constexpr size_t kRingACtlOff = 0x3000;  // RingControl            (4 KiB page)  MC -> GTA
inline constexpr size_t kRingBCtlOff = 0x4000;  // RingControl            (4 KiB page)  GTA -> MC
inline constexpr size_t kRingAOff = 0x5000;     // ring A data
inline constexpr size_t kRingACap = size_t{4} << 20;  // 4 MiB: bulk voxel streaming
inline constexpr size_t kRingBOff = kRingAOff + kRingACap;
inline constexpr size_t kRingBCap = size_t{512} << 10;  // 512 KiB: interaction events
inline constexpr size_t kTotalSize = kRingBOff + kRingBCap;

// Default object names. Windows: named pagefile-backed section. Elsewhere (tests): file under /dev/shm or $TMPDIR.
inline constexpr const char* kDefaultName = "OrbitalCannonPassthrough_v1";

// ---------------------------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------------------------
enum InitState : uint32_t { kInitRaw = 0, kInitBusy = 1, kInitReady = 2 };

struct SharedHeader {
	uint32_t magic;             //   0
	uint32_t version;           //   4
	uint32_t total_size;        //   8
	uint32_t init_state;        //  12  atomic, see InitState
	uint32_t ring_a_capacity;   //  16
	uint32_t ring_b_capacity;   //  20
	uint32_t reserved0[10];     //  24
	uint64_t mc_epoch;          //  64  random per Minecraft process start; the peer resyncs when it changes
	uint64_t mc_heartbeat;      //  72  incremented every server tick
	uint8_t pad1[48];           //  80
	uint64_t gta_epoch;         // 128  random per GTA script start
	uint64_t gta_heartbeat;     // 136  incremented every rendered frame
	uint8_t pad2[48];           // 144
	uint8_t reserved1[3904];    // 192 -> 4096
};
static_assert(sizeof(SharedHeader) == 4096);
static_assert(offsetof(SharedHeader, init_state) == 12);
static_assert(offsetof(SharedHeader, mc_epoch) == 64);
static_assert(offsetof(SharedHeader, mc_heartbeat) == 72);
static_assert(offsetof(SharedHeader, gta_epoch) == 128);
static_assert(offsetof(SharedHeader, gta_heartbeat) == 136);

// ---------------------------------------------------------------------------------------------------------------
// Ring control block
// ---------------------------------------------------------------------------------------------------------------
struct RingControl {
	uint64_t head;       //   0  producer cursor (bytes ever written), written only by the producer
	uint8_t pad0[56];
	uint64_t tail;       //  64  consumer cursor (bytes ever consumed), written only by the consumer
	uint8_t pad1[56];
	uint32_t capacity;   // 128  power of two, set once at init
	uint32_t mask;       // 132
	uint64_t dropped;    // 136  producer-side diagnostic: records refused because the ring was full
	uint64_t records;    // 144  producer-side diagnostic: records published
	uint8_t pad2[104];   // 152 -> 256
};
static_assert(sizeof(RingControl) == 256);
static_assert(offsetof(RingControl, head) == 0);
static_assert(offsetof(RingControl, tail) == 64);
static_assert(offsetof(RingControl, capacity) == 128);

// ---------------------------------------------------------------------------------------------------------------
// Record framing (both rings)
// ---------------------------------------------------------------------------------------------------------------
struct MsgHeader {
	uint16_t type;
	uint16_t flags;  // reserved, 0
	uint32_t len;    // payload bytes, excluding this header and trailing pad to 8
};
static_assert(sizeof(MsgHeader) == 8);

inline constexpr uint32_t kMaxPayload = 16384;
constexpr uint32_t align8(uint32_t n) { return (n + 7u) & ~7u; }
constexpr uint32_t record_size(uint32_t payload_len) { return 8u + align8(payload_len); }

enum MsgType : uint16_t {
	kMsgPad = 0,  // padding to the end of the ring so a record never straddles the wrap point

	// Shared by both rings
	kMsgPing = 7,
	kMsgPong = 8,

	// Ring A: Minecraft -> GTA
	kMsgSessionStart = 1,
	kMsgPaletteEntry = 2,
	kMsgSectionData = 3,
	kMsgSectionUnload = 4,
	kMsgVoxelUpdates = 5,
	kMsgAck = 6,

	// Ring B: GTA -> Minecraft
	kMsgHello = 16,
	kMsgAnchor = 17,
	kMsgBreakRequest = 18,
	kMsgPlaceRequest = 19,
	kMsgCollision = 20,
};

// ---- Ring A payloads ------------------------------------------------------------------------------------------

// Sent first in every session and again whenever the GTA side says HELLO. The receiver drops all voxel state.
struct SessionStart {
	uint64_t mc_epoch;    //  0
	uint32_t sync_serial; //  8  bumps on every full resync
	uint32_t flags;       // 12
	double anchor_x;      // 16  Minecraft-space point that GTA's ANCHOR is pinned to (world coordinates)
	double anchor_y;      // 24
	double anchor_z;      // 32
	int32_t min_y;        // 40  build height range of the dimension
	int32_t max_y;        // 44
};
static_assert(sizeof(SessionStart) == 48);

// Announces a voxel id before any record that uses it. Ids are assigned by the Minecraft side per session.
struct PaletteEntryHdr {
	uint16_t id;        // 0   1..65535; 0 is air and never announced
	uint16_t flags;     // 2   PaletteFlags
	uint32_t rgb;       // 4   0x00RRGGBB map colour
	uint16_t name_len;  // 8
	uint16_t reserved;  // 10
	// char name[name_len] follows (registry id, e.g. "minecraft:stone"), not NUL terminated
};
static_assert(sizeof(PaletteEntryHdr) == 12);
enum PaletteFlags : uint16_t {
	kPalOpaque = 1u << 0,     // hides the faces of neighbouring voxels
	kPalPlaceable = 1u << 1,  // has an item form, so it can be offered in the GTA hotbar
	kPalTranslucent = 1u << 2,
};

struct SectionDataHdr {
	int32_t sx, sy, sz;   //  0  section coordinates (block >> 4)
	uint32_t version;     // 12  monotonically increasing per section, diagnostics only
	uint16_t count;       // 16  number of VoxelEntry records following
	uint16_t flags;       // 18  SectionFlags
	uint32_t reserved;    // 20
	// VoxelEntry entries[count] follow
};
static_assert(sizeof(SectionDataHdr) == 24);
enum SectionFlags : uint16_t { kSecReplace = 1u << 0, kSecLast = 1u << 1 };

// One visible voxel inside a section. local = x | (z << 4) | (y << 8), each component 0..15.
struct VoxelEntry {
	uint16_t local;
	uint16_t id;  // palette id, never 0
};
static_assert(sizeof(VoxelEntry) == 4);
inline constexpr uint32_t kMaxEntriesPerSectionMsg = (kMaxPayload - sizeof(SectionDataHdr)) / sizeof(VoxelEntry);

struct SectionUnload {
	int32_t sx, sy, sz;  // 12 bytes
};
static_assert(sizeof(SectionUnload) == 12);

struct VoxelUpdatesHdr {
	uint16_t count;     // 0
	uint16_t reserved;  // 2
	uint32_t tick;      // 4  Minecraft server tick that produced the batch
	// VoxelUpdate entries[count] follow
};
static_assert(sizeof(VoxelUpdatesHdr) == 8);

// Set (id != 0) or clear (id == 0) one voxel in the visible shell, in Minecraft block coordinates.
struct VoxelUpdate {
	int32_t x, y, z;  //  0
	uint16_t id;      // 12
	uint16_t flags;   // 14  UpdateFlags
};
static_assert(sizeof(VoxelUpdate) == 16);
enum UpdateFlags : uint16_t { kUpdFromGta = 1u << 0 };  // set when caused by a request from this client
inline constexpr uint32_t kMaxUpdatesPerMsg = (kMaxPayload - sizeof(VoxelUpdatesHdr)) / sizeof(VoxelUpdate);

enum AckResult : uint16_t {
	kAckBroken = 0,
	kAckDamaged = 1,
	kAckPlaced = 2,
	kAckDeniedReach = 3,
	kAckDeniedUnloaded = 4,
	kAckDeniedUnbreakable = 5,
	kAckDeniedOccupied = 6,
	kAckDeniedAir = 7,
	kAckDeniedHitbox = 8,
	kAckDeniedRate = 9,
	kAckDeniedHeight = 10,
	kAckDeniedNoAnchor = 11,
	kAckDeniedBadPalette = 12,
};
struct Ack {
	uint32_t request_seq;  //  0
	uint16_t result;       //  4  AckResult
	uint16_t reason;       //  6  reserved
	int32_t x, y, z;       //  8
	float progress;        // 20  accumulated block damage as a fraction of its hardness (0..1)
};
static_assert(sizeof(Ack) == 24);

// ---- Shared payloads ------------------------------------------------------------------------------------------
struct Ping {
	uint64_t token;  // echoed unchanged in PONG; the sender measures round trip on its own clock
};
static_assert(sizeof(Ping) == 8);

// ---- Ring B payloads ------------------------------------------------------------------------------------------
struct Hello {
	uint64_t gta_epoch;     //  0
	uint32_t version;       //  8
	uint32_t flags;         // 12
	float view_radius_h;    // 16  horizontal stream radius in blocks
	float view_radius_v;    // 20  vertical stream radius in blocks
	uint32_t prop_budget;   // 24  advisory: how many props the renderer will keep alive
	uint32_t reserved;      // 28
};
static_assert(sizeof(Hello) == 32);

// Pins GTA world point (gx,gy,gz) to the Minecraft anchor announced in SessionStart.
struct Anchor {
	double gx, gy, gz;  //  0
	uint32_t serial;    // 24
	uint32_t reserved;  // 28
};
static_assert(sizeof(Anchor) == 32);

// Minecraft Direction.get3DDataValue() order. Face is the face of the target voxel that was hit.
enum Face : uint8_t { kDown = 0, kUp = 1, kNorth = 2, kSouth = 3, kWest = 4, kEast = 5 };
enum BreakSource : uint8_t { kSrcBullet = 1, kSrcMelee = 2, kSrcExplosive = 3, kSrcTool = 4, kSrcVehicle = 5 };

struct BreakRequest {
	uint32_t seq;          //  0
	uint8_t source;        //  4  BreakSource
	uint8_t face;          //  5  Face
	uint16_t reserved;     //  6
	int32_t x, y, z;       //  8  Minecraft block coordinates of the voxel that was hit
	float damage;          // 20  GTA weapon damage (or tool strength)
	uint32_t weapon_hash;  // 24
	uint32_t reserved2;    // 28
};
static_assert(sizeof(BreakRequest) == 32);

struct PlaceRequest {
	uint32_t seq;          //  0
	uint8_t face;          //  4  Face of the clicked voxel; the new block goes on the far side of it
	uint8_t reserved0;     //  5
	uint16_t palette_id;   //  6  block to place
	int32_t x, y, z;       //  8  clicked voxel
	uint32_t reserved1;    // 20
};
static_assert(sizeof(PlaceRequest) == 24);

enum CollisionKind : uint8_t { kColPed = 1, kColVehicle = 2 };
struct CollisionEvent {
	uint8_t kind;          //  0  CollisionKind
	uint8_t flags;         //  1
	int8_t nx, ny, nz;     //  2  contact normal in Minecraft axes, components in {-1,0,1}
	uint8_t reserved[3];   //  5
	int32_t x, y, z;       //  8  contact voxel, Minecraft block coordinates
	float speed;           // 20  speed before impact, m/s
	float delta_v;         // 24  speed lost in the impact frame, m/s
	float mass;            // 28  mass estimate in kg (0 = unknown)
};
static_assert(sizeof(CollisionEvent) == 32);

// ---------------------------------------------------------------------------------------------------------------
// Seqlock slots
// ---------------------------------------------------------------------------------------------------------------
enum PlayerFlags : uint32_t {
	kPfOnFoot = 1u << 0,
	kPfInVehicle = 1u << 1,
	kPfShooting = 1u << 2,
	kPfDead = 1u << 3,
	kPfRagdoll = 1u << 4,
	kPfAnchored = 1u << 5,  // GTA side has an active anchor
};

struct PlayerState {
	uint64_t frame;          //   0  GTA frame counter
	double px, py, pz;       //   8  feet position, GTA world coordinates
	float vx, vy, vz;        //  32  velocity m/s, GTA axes
	float heading;           //  44  degrees, GTA convention
	double ex, ey, ez;       //  48  camera origin = aim ray origin
	float ax, ay, az;        //  72  aim direction, unit vector, GTA axes
	float hit_min[3];        //  84  hitbox corner offsets relative to (px,py,pz), GTA axes
	float hit_max[3];        //  96
	uint32_t flags;          // 108  PlayerFlags
	uint32_t weapon_hash;    // 112
	uint32_t vehicle_model;  // 116  0 on foot
	float vehicle_speed;     // 120
	uint32_t reserved;       // 124
};
static_assert(sizeof(PlayerState) == 128);
static_assert(offsetof(PlayerState, px) == 8);
static_assert(offsetof(PlayerState, vx) == 32);
static_assert(offsetof(PlayerState, ex) == 48);
static_assert(offsetof(PlayerState, ax) == 72);
static_assert(offsetof(PlayerState, hit_min) == 84);
static_assert(offsetof(PlayerState, flags) == 108);

struct McState {
	uint64_t tick;               //  0
	int64_t day_time;            //  8
	float mspt;                  // 16  smoothed milliseconds per server tick
	uint32_t flags;              // 20
	uint32_t sections_streamed;  // 24
	uint32_t sections_pending;   // 28
	uint32_t reserved[4];        // 32
};
static_assert(sizeof(McState) == 48);

template <class T>
struct SeqSlot {
	uint32_t seq;        //  0  even = stable, odd = write in progress
	uint8_t pad[60];     //  4
	T data;              // 64
};
static_assert(offsetof(SeqSlot<PlayerState>, data) == 64);
static_assert(sizeof(SeqSlot<PlayerState>) <= 4096 && sizeof(SeqSlot<McState>) <= 4096);

// ---------------------------------------------------------------------------------------------------------------
// Atomic helpers (acquire/release on plain fields inside the mapping)
// ---------------------------------------------------------------------------------------------------------------
inline uint64_t load_acquire(const uint64_t& v) {
	return std::atomic_ref<const uint64_t>(v).load(std::memory_order_acquire);
}
inline void store_release(uint64_t& v, uint64_t x) { std::atomic_ref<uint64_t>(v).store(x, std::memory_order_release); }
inline uint64_t load_relaxed(const uint64_t& v) {
	return std::atomic_ref<const uint64_t>(v).load(std::memory_order_relaxed);
}
inline uint32_t load_acquire(const uint32_t& v) {
	return std::atomic_ref<const uint32_t>(v).load(std::memory_order_acquire);
}
inline void store_release(uint32_t& v, uint32_t x) { std::atomic_ref<uint32_t>(v).store(x, std::memory_order_release); }
inline uint64_t fetch_add_relaxed(uint64_t& v, uint64_t x) {
	return std::atomic_ref<uint64_t>(v).fetch_add(x, std::memory_order_relaxed);
}
inline bool cas_u32(uint32_t& v, uint32_t expected, uint32_t desired) {
	return std::atomic_ref<uint32_t>(v).compare_exchange_strong(expected, desired, std::memory_order_acq_rel);
}

// ---------------------------------------------------------------------------------------------------------------
// Ring writer (single producer)
// ---------------------------------------------------------------------------------------------------------------
class RingWriter {
public:
	RingWriter() = default;
	RingWriter(RingControl* ctl, uint8_t* data) : ctl_(ctl), data_(data), cap_(ctl->capacity), mask_(ctl->mask) {
		pending_ = load_acquire(ctl_->head);
		cached_tail_ = load_acquire(ctl_->tail);
	}

	bool valid() const { return ctl_ != nullptr; }

	// Reserves a record and returns a pointer to its payload (len bytes, zero padding already applied up to 8).
	// Nothing is visible to the consumer until commit(). Returns nullptr when the ring is full or len is invalid.
	uint8_t* begin(uint16_t type, uint32_t len) {
		if (len > kMaxPayload) return nullptr;
		const uint32_t rec = record_size(len);
		uint32_t pos = static_cast<uint32_t>(pending_ & mask_);
		uint32_t pad = (pos + rec > cap_) ? (cap_ - pos) : 0;
		uint64_t need = static_cast<uint64_t>(pad) + rec;
		if (cap_ - (pending_ - cached_tail_) < need) {
			cached_tail_ = load_acquire(ctl_->tail);
			if (cap_ - (pending_ - cached_tail_) < need) {
				fetch_add_relaxed(ctl_->dropped, 1);
				return nullptr;
			}
		}
		if (pad) {
			MsgHeader ph{kMsgPad, 0, pad - 8u};
			std::memcpy(data_ + pos, &ph, sizeof ph);
			pending_ += pad;
			pos = 0;
		}
		MsgHeader h{type, 0, len};
		std::memcpy(data_ + pos, &h, sizeof h);
		uint8_t* payload = data_ + pos + sizeof h;
		const uint32_t tail_pad = align8(len) - len;
		if (tail_pad) std::memset(payload + len, 0, tail_pad);
		pending_ += rec;
		++unpublished_;
		return payload;
	}

	bool write(uint16_t type, const void* payload, uint32_t len) {
		uint8_t* p = begin(type, len);
		if (!p) return false;
		if (len) std::memcpy(p, payload, len);
		commit();
		return true;
	}

	// Makes every record reserved since the last commit visible to the consumer.
	void commit() {
		if (unpublished_ == 0) return;
		store_release(ctl_->head, pending_);
		fetch_add_relaxed(ctl_->records, unpublished_);
		unpublished_ = 0;
	}

	// Free bytes as seen right now (conservative: uses the cached tail unless refreshed).
	uint64_t free_bytes() {
		cached_tail_ = load_acquire(ctl_->tail);
		return cap_ - (pending_ - cached_tail_);
	}

private:
	RingControl* ctl_ = nullptr;
	uint8_t* data_ = nullptr;
	uint32_t cap_ = 0, mask_ = 0;
	uint64_t pending_ = 0;      // next write cursor, including uncommitted records
	uint64_t cached_tail_ = 0;
	uint64_t unpublished_ = 0;  // records reserved since the last commit
};

// ---------------------------------------------------------------------------------------------------------------
// Ring reader (single consumer)
// ---------------------------------------------------------------------------------------------------------------
class RingReader {
public:
	RingReader() = default;
	RingReader(RingControl* ctl, const uint8_t* data) : ctl_(ctl), data_(data), cap_(ctl->capacity), mask_(ctl->mask) {
		cursor_ = load_acquire(ctl_->tail);
	}

	bool valid() const { return ctl_ != nullptr; }
	uint64_t corrupt_count() const { return corrupt_; }

	// Bytes waiting right now.
	uint64_t available() const { return load_acquire(ctl_->head) - cursor_; }

	// Discards everything currently in the ring (used when attaching to a ring that may hold a dead session's data).
	void skip_all() {
		cursor_ = load_acquire(ctl_->head);
		store_release(ctl_->tail, cursor_);
	}

	// Calls handler(type, payload_ptr, payload_len) for each record, in order. The payload pointer is valid only
	// during the call. Returns the number of records delivered. Stops after max_records.
	template <class F>
	uint32_t drain(F&& handler, uint32_t max_records = 0xFFFFFFFFu) {
		const uint64_t head = load_acquire(ctl_->head);
		uint32_t delivered = 0;
		while (cursor_ != head && delivered < max_records) {
			const uint64_t avail = head - cursor_;
			const uint32_t pos = static_cast<uint32_t>(cursor_ & mask_);
			MsgHeader h;
			std::memcpy(&h, data_ + pos, sizeof h);
			const uint64_t rec = 8ull + ((static_cast<uint64_t>(h.len) + 7ull) & ~7ull);
			// rec is computed in 64 bits, so a hostile len cannot overflow it.
			if (rec > avail || pos + rec > cap_ || (h.type != kMsgPad && h.len > kMaxPayload)) {
				// Malformed or torn record: the peer is buggy or restarted mid-write. Resynchronise.
				++corrupt_;
				cursor_ = head;
				break;
			}
			if (h.type != kMsgPad) {
				handler(h.type, data_ + pos + sizeof h, h.len);
				++delivered;
			}
			cursor_ += rec;
		}
		store_release(ctl_->tail, cursor_);
		return delivered;
	}

private:
	RingControl* ctl_ = nullptr;
	const uint8_t* data_ = nullptr;
	uint32_t cap_ = 0, mask_ = 0;
	uint64_t cursor_ = 0;
	uint64_t corrupt_ = 0;
};

// ---------------------------------------------------------------------------------------------------------------
// Seqlock slot access
// ---------------------------------------------------------------------------------------------------------------
template <class T>
inline void seq_write(SeqSlot<T>& slot, const T& value) {
	static_assert(std::is_trivially_copyable_v<T> && sizeof(T) % 8 == 0);
	const uint32_t s = load_acquire(slot.seq);  // single writer: only we modify seq
	store_release(slot.seq, s + 1);             // odd: write in progress
	std::atomic_thread_fence(std::memory_order_release);
	const uint64_t* src = reinterpret_cast<const uint64_t*>(&value);
	uint64_t* dst = reinterpret_cast<uint64_t*>(&slot.data);
	for (size_t i = 0; i < sizeof(T) / 8; ++i) std::atomic_ref<uint64_t>(dst[i]).store(src[i], std::memory_order_relaxed);
	store_release(slot.seq, s + 2);  // even: stable
}

// Returns false if no value was ever written. Retries internally while a write is in flight.
template <class T>
inline bool seq_read(const SeqSlot<T>& slot, T& out) {
	static_assert(std::is_trivially_copyable_v<T> && sizeof(T) % 8 == 0);
	for (int spin = 0; spin < 1000; ++spin) {
		const uint32_t s1 = load_acquire(slot.seq);
		if (s1 == 0) return false;
		if (s1 & 1u) continue;
		uint64_t* dst = reinterpret_cast<uint64_t*>(&out);
		const uint64_t* src = reinterpret_cast<const uint64_t*>(&slot.data);
		for (size_t i = 0; i < sizeof(T) / 8; ++i) dst[i] = std::atomic_ref<const uint64_t>(src[i]).load(std::memory_order_relaxed);
		std::atomic_thread_fence(std::memory_order_acquire);
		if (load_acquire(slot.seq) == s1) return true;
	}
	return false;
}

// ---------------------------------------------------------------------------------------------------------------
// View over a mapped region
// ---------------------------------------------------------------------------------------------------------------
struct Layout {
	uint8_t* base = nullptr;
	SharedHeader* hdr = nullptr;
	SeqSlot<PlayerState>* gta_state = nullptr;
	SeqSlot<McState>* mc_state = nullptr;
	RingControl* ring_a_ctl = nullptr;
	RingControl* ring_b_ctl = nullptr;
	uint8_t* ring_a = nullptr;
	uint8_t* ring_b = nullptr;

	static Layout over(void* mapped) {
		Layout l;
		l.base = static_cast<uint8_t*>(mapped);
		l.hdr = reinterpret_cast<SharedHeader*>(l.base + kHeaderOff);
		l.gta_state = reinterpret_cast<SeqSlot<PlayerState>*>(l.base + kGtaStateOff);
		l.mc_state = reinterpret_cast<SeqSlot<McState>*>(l.base + kMcStateOff);
		l.ring_a_ctl = reinterpret_cast<RingControl*>(l.base + kRingACtlOff);
		l.ring_b_ctl = reinterpret_cast<RingControl*>(l.base + kRingBCtlOff);
		l.ring_a = l.base + kRingAOff;
		l.ring_b = l.base + kRingBOff;
		return l;
	}
};

enum class AttachResult { kOk, kBadMagic, kBadVersion, kBadSize, kTimeout };

// Idempotent, race-free initialisation. Whichever process gets there first formats the region; the other waits for
// it. A freshly created mapping is zero filled on every platform we support, which is what kInitRaw relies on.
inline AttachResult init_or_validate(Layout& l, uint32_t timeout_spins = 2'000'000) {
	SharedHeader& h = *l.hdr;
	if (cas_u32(h.init_state, kInitRaw, kInitBusy)) {
		h.magic = kMagic;
		h.version = kVersion;
		h.total_size = static_cast<uint32_t>(kTotalSize);
		h.ring_a_capacity = static_cast<uint32_t>(kRingACap);
		h.ring_b_capacity = static_cast<uint32_t>(kRingBCap);
		l.ring_a_ctl->capacity = static_cast<uint32_t>(kRingACap);
		l.ring_a_ctl->mask = static_cast<uint32_t>(kRingACap - 1);
		l.ring_b_ctl->capacity = static_cast<uint32_t>(kRingBCap);
		l.ring_b_ctl->mask = static_cast<uint32_t>(kRingBCap - 1);
		store_release(h.init_state, kInitReady);
	} else {
		uint32_t spins = 0;
		while (load_acquire(h.init_state) != kInitReady) {
			if (++spins > timeout_spins) return AttachResult::kTimeout;
		}
	}
	if (h.magic != kMagic) return AttachResult::kBadMagic;
	if (h.version != kVersion) return AttachResult::kBadVersion;
	if (h.total_size != kTotalSize || h.ring_a_capacity != kRingACap || h.ring_b_capacity != kRingBCap) {
		return AttachResult::kBadSize;
	}
	return AttachResult::kOk;
}

}  // namespace ocp
