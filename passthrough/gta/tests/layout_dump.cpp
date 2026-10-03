// Prints the C++ compiler's view of the wire protocol (struct sizes, field offsets, enum values, region offsets) as
// "name value" lines. minecraft/.../LayoutParityTest diffs this against the Java mirror, so any drift between
// orbital_ipc.h and Protocol.java fails a test instead of corrupting memory at run time.
#include <cstddef>
#include <cstdio>

#include "orbital_ipc.h"

using namespace ocp;

#define S(T) std::printf("%s %zu\n", #T, sizeof(T))
#define F(T, f) std::printf("%s.%s %zu\n", #T, #f, offsetof(T, f))
#define C(n) std::printf("C.%s %lld\n", #n, static_cast<long long>(n))

int main() {
	S(SharedHeader); F(SharedHeader, magic); F(SharedHeader, version); F(SharedHeader, total_size); F(SharedHeader, init_state);
	F(SharedHeader, ring_a_capacity); F(SharedHeader, ring_b_capacity); F(SharedHeader, mc_epoch); F(SharedHeader, mc_heartbeat);
	F(SharedHeader, gta_epoch); F(SharedHeader, gta_heartbeat);
	S(RingControl); F(RingControl, head); F(RingControl, tail); F(RingControl, capacity); F(RingControl, mask);
	F(RingControl, dropped); F(RingControl, records);
	S(MsgHeader); F(MsgHeader, type); F(MsgHeader, flags); F(MsgHeader, len);
	S(SessionStart); F(SessionStart, mc_epoch); F(SessionStart, sync_serial); F(SessionStart, flags); F(SessionStart, anchor_x);
	F(SessionStart, anchor_y); F(SessionStart, anchor_z); F(SessionStart, min_y); F(SessionStart, max_y);
	S(PaletteEntryHdr); F(PaletteEntryHdr, id); F(PaletteEntryHdr, flags); F(PaletteEntryHdr, rgb); F(PaletteEntryHdr, name_len);
	F(PaletteEntryHdr, reserved);
	S(SectionDataHdr); F(SectionDataHdr, sx); F(SectionDataHdr, sy); F(SectionDataHdr, sz); F(SectionDataHdr, version);
	F(SectionDataHdr, count); F(SectionDataHdr, flags); F(SectionDataHdr, reserved);
	S(VoxelEntry); F(VoxelEntry, local); F(VoxelEntry, id);
	S(SectionUnload); F(SectionUnload, sx); F(SectionUnload, sy); F(SectionUnload, sz);
	S(VoxelUpdatesHdr); F(VoxelUpdatesHdr, count); F(VoxelUpdatesHdr, reserved); F(VoxelUpdatesHdr, tick);
	S(VoxelUpdate); F(VoxelUpdate, x); F(VoxelUpdate, y); F(VoxelUpdate, z); F(VoxelUpdate, id); F(VoxelUpdate, flags);
	S(Ack); F(Ack, request_seq); F(Ack, result); F(Ack, reason); F(Ack, x); F(Ack, y); F(Ack, z); F(Ack, progress);
	S(Ping); F(Ping, token);
	S(Hello); F(Hello, gta_epoch); F(Hello, version); F(Hello, flags); F(Hello, view_radius_h); F(Hello, view_radius_v);
	F(Hello, prop_budget); F(Hello, reserved);
	S(Anchor); F(Anchor, gx); F(Anchor, gy); F(Anchor, gz); F(Anchor, serial); F(Anchor, reserved);
	S(BreakRequest); F(BreakRequest, seq); F(BreakRequest, source); F(BreakRequest, face); F(BreakRequest, reserved);
	F(BreakRequest, x); F(BreakRequest, y); F(BreakRequest, z); F(BreakRequest, damage); F(BreakRequest, weapon_hash);
	F(BreakRequest, reserved2);
	S(PlaceRequest); F(PlaceRequest, seq); F(PlaceRequest, face); F(PlaceRequest, reserved0); F(PlaceRequest, palette_id);
	F(PlaceRequest, x); F(PlaceRequest, y); F(PlaceRequest, z); F(PlaceRequest, reserved1);
	S(CollisionEvent); F(CollisionEvent, kind); F(CollisionEvent, flags); F(CollisionEvent, nx); F(CollisionEvent, ny);
	F(CollisionEvent, nz); F(CollisionEvent, x); F(CollisionEvent, y); F(CollisionEvent, z); F(CollisionEvent, speed);
	F(CollisionEvent, delta_v); F(CollisionEvent, mass);
	S(PlayerState); F(PlayerState, frame); F(PlayerState, px); F(PlayerState, py); F(PlayerState, pz); F(PlayerState, vx);
	F(PlayerState, vy); F(PlayerState, vz); F(PlayerState, heading); F(PlayerState, ex); F(PlayerState, ey); F(PlayerState, ez);
	F(PlayerState, ax); F(PlayerState, ay); F(PlayerState, az); F(PlayerState, hit_min); F(PlayerState, hit_max);
	F(PlayerState, flags); F(PlayerState, weapon_hash); F(PlayerState, vehicle_model); F(PlayerState, vehicle_speed);
	F(PlayerState, reserved);
	S(McState); F(McState, tick); F(McState, day_time); F(McState, mspt); F(McState, flags); F(McState, sections_streamed);
	F(McState, sections_pending);
	std::printf("SeqSlot.data %zu\n", offsetof(SeqSlot<PlayerState>, data));

	C(kMagic); C(kVersion); C(kHeaderOff); C(kGtaStateOff); C(kMcStateOff); C(kRingACtlOff); C(kRingBCtlOff); C(kRingAOff);
	C(kRingACap); C(kRingBOff); C(kRingBCap); C(kTotalSize); C(kMaxPayload); C(kMaxEntriesPerSectionMsg); C(kMaxUpdatesPerMsg);
	C(kInitRaw); C(kInitBusy); C(kInitReady);
	C(kMsgPad); C(kMsgPing); C(kMsgPong); C(kMsgSessionStart); C(kMsgPaletteEntry); C(kMsgSectionData); C(kMsgSectionUnload);
	C(kMsgVoxelUpdates); C(kMsgAck); C(kMsgHello); C(kMsgAnchor); C(kMsgBreakRequest); C(kMsgPlaceRequest); C(kMsgCollision);
	C(kPalOpaque); C(kPalPlaceable); C(kPalTranslucent); C(kSecReplace); C(kSecLast); C(kUpdFromGta);
	C(kAckBroken); C(kAckDamaged); C(kAckPlaced); C(kAckDeniedReach); C(kAckDeniedUnloaded); C(kAckDeniedUnbreakable);
	C(kAckDeniedOccupied); C(kAckDeniedAir); C(kAckDeniedHitbox); C(kAckDeniedRate); C(kAckDeniedHeight); C(kAckDeniedNoAnchor);
	C(kAckDeniedBadPalette);
	C(kDown); C(kUp); C(kNorth); C(kSouth); C(kWest); C(kEast);
	C(kSrcBullet); C(kSrcMelee); C(kSrcExplosive); C(kSrcTool); C(kSrcVehicle);
	C(kColPed); C(kColVehicle);
	C(kPfOnFoot); C(kPfInVehicle); C(kPfShooting); C(kPfDead); C(kPfRagdoll); C(kPfAnchored);
	return 0;
}
