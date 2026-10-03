// Full-stack GTA-side tests: the real App, Bridge, WorldSync, PropManager and Interaction running over a real shared
// memory file against a mock GTA, with a fake Minecraft peer driving the other end of the rings.
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <random>
#include <cstring>
#include <string>
#include <vector>

#include "mock_game.h"
#include "test_util.h"
#include "../src/core/app.h"
#include "../src/core/config.h"
#include "../src/core/raycast.h"

using namespace opt;

namespace {

struct Msg {
	uint16_t type;
	std::vector<uint8_t> bytes;
	template <class T>
	T as() const {
		T v{};
		std::memcpy(&v, bytes.data(), std::min(sizeof(T), bytes.size()));
		return v;
	}
};

// The Minecraft half of the channel, written in C++ so the GTA-side logic can be tested in-process.
struct FakeMc {
	ocp::SharedMapping map;
	ocp::Layout L;
	ocp::RingWriter wa;
	ocp::RingReader rb;
	std::string name;

	explicit FakeMc(const std::string& n) : name(n) {
		std::string err;
		const bool ok = map.open(name, &err);
		if (!ok) std::printf("FakeMc open failed: %s\n", err.c_str());
		L = ocp::Layout::over(map.data());
		ocp::init_or_validate(L);
		ocp::store_release(L.hdr->mc_epoch, 0xABCDEF01ull);
		wa = ocp::RingWriter(L.ring_a_ctl, L.ring_a);
		rb = ocp::RingReader(L.ring_b_ctl, L.ring_b);
	}
	~FakeMc() {
		map.close();
		::unlink(ocp::resolveMappingName(name).c_str());
	}
	void beat() { ocp::fetch_add_relaxed(L.hdr->mc_heartbeat, 1); }
	template <class T>
	void send(uint16_t type, const T& v) {
		CHECK(wa.write(type, &v, sizeof(T)));
	}
	void sendRaw(uint16_t type, const std::vector<uint8_t>& b) { CHECK(wa.write(type, b.data(), static_cast<uint32_t>(b.size()))); }
	void session(double ax, double ay, double az) {
		ocp::SessionStart s{};
		s.mc_epoch = 0xABCDEF01ull;
		s.sync_serial = 1;
		s.anchor_x = ax;
		s.anchor_y = ay;
		s.anchor_z = az;
		s.min_y = -64;
		s.max_y = 320;
		send(ocp::kMsgSessionStart, s);
	}
	void palette(uint16_t id, uint16_t flags, uint32_t rgb, const std::string& nm) {
		std::vector<uint8_t> b(sizeof(ocp::PaletteEntryHdr) + nm.size());
		ocp::PaletteEntryHdr h{id, flags, rgb, static_cast<uint16_t>(nm.size()), 0};
		std::memcpy(b.data(), &h, sizeof h);
		std::memcpy(b.data() + sizeof h, nm.data(), nm.size());
		sendRaw(ocp::kMsgPaletteEntry, b);
	}
	// A flat plate of voxels at y = 63 covering [-half, half]^2, split into one record per section.
	void plate(int half, uint16_t id) {
		for (int sx = floorDiv16(-half); sx <= floorDiv16(half); ++sx)
			for (int sz = floorDiv16(-half); sz <= floorDiv16(half); ++sz) {
				std::vector<ocp::VoxelEntry> es;
				for (int lx = 0; lx < 16; ++lx)
					for (int lz = 0; lz < 16; ++lz) {
						const int x = sx * 16 + lx, z = sz * 16 + lz;
						if (x < -half || x > half || z < -half || z > half) continue;
						es.push_back({static_cast<uint16_t>(lx | (lz << 4) | (15 << 8)), id});  // y = 63 -> section 3, local 15
					}
				ocp::SectionDataHdr h{};
				h.sx = sx;
				h.sy = 3;
				h.sz = sz;
				h.count = static_cast<uint16_t>(es.size());
				h.flags = ocp::kSecReplace | ocp::kSecLast;
				std::vector<uint8_t> b;
				b.reserve(sizeof h + es.size() * sizeof(ocp::VoxelEntry));
				const auto* hp = reinterpret_cast<const uint8_t*>(&h);
				b.insert(b.end(), hp, hp + sizeof h);
				const auto* ep = reinterpret_cast<const uint8_t*>(es.data());
				b.insert(b.end(), ep, ep + es.size() * sizeof(ocp::VoxelEntry));
				sendRaw(ocp::kMsgSectionData, b);
			}
	}
	void update(int x, int y, int z, uint16_t id, uint16_t flags) {
		std::vector<uint8_t> b(sizeof(ocp::VoxelUpdatesHdr) + sizeof(ocp::VoxelUpdate));
		ocp::VoxelUpdatesHdr h{1, 0, 1};
		ocp::VoxelUpdate u{x, y, z, id, flags};
		std::memcpy(b.data(), &h, sizeof h);
		std::memcpy(b.data() + sizeof h, &u, sizeof u);
		sendRaw(ocp::kMsgVoxelUpdates, b);
	}
	std::vector<Msg> recv() {
		std::vector<Msg> out;
		rb.drain([&](uint16_t t, const uint8_t* p, uint32_t len) { out.push_back({t, std::vector<uint8_t>(p, p + len)}); });
		return out;
	}
};

Config testConfig(tst::MockGame& game, const std::string& shm) {
	Config c;
	c.shmName = shm;
	c.model = "prop_test_cube";
	game.validModels.insert(game.joaat("prop_test_cube"));
	c.props.maxProps = 400;
	c.props.spawnRadius = 12;
	c.overlay = false;
	return c;
}

struct Harness {
	tst::MockGame game;
	std::string shm;
	FakeMc mc;
	App app;
	explicit Harness(const std::string& tag)
		: shm("ocp_test_" + tag + "_" + std::to_string(::getpid())), mc(shm), app(game, testConfig(game, shm)) {
		game.player.valid = true;
		game.player.ped = 7;
		game.player.feet = {1000, 2000, 30};
		game.player.flags = ocp::kPfOnFoot;
		game.player.camPos = {1000, 1994, 34};
		game.player.camDir = normalized(Vec3{0, 6, -4});
		game.player.hitMin[0] = game.player.hitMin[1] = -0.35f;
		game.player.hitMax[0] = game.player.hitMax[1] = 0.35f;
		game.player.hitMin[2] = 0;
		game.player.hitMax[2] = 1.8f;
	}
	void frames(int n = 1) {
		for (int i = 0; i < n; ++i) {
			game.clockUs += 16'667;
			mc.beat();
			app.frame();
		}
	}
	// Brings both sides to the fully anchored, streamed state used by most tests.
	void connectAndAnchor(int plateHalf = 8) {
		frames(2);                                          // attach, heartbeat seen, HELLO queued
		mc.session(0.5, 64.0, 0.5);
		mc.palette(1, ocp::kPalOpaque | ocp::kPalPlaceable, 0x7F7F7F, "minecraft:stone");
		mc.palette(2, ocp::kPalPlaceable, 0x654321, "minecraft:oak_planks");
		mc.plate(plateHalf, 1);
		frames(2);
		app.onKey(0x78);  // F9: anchor at the player's feet
		frames(30);
	}
};

const Msg* find(const std::vector<Msg>& v, uint16_t type) {
	for (const Msg& m : v)
		if (m.type == type) return &m;
	return nullptr;
}
}  // namespace

TEST(app_handshake_hello_session_palette_and_anchor) {
	Harness h("handshake");
	h.frames(3);
	CHECK(h.app.bridge().attached());
	CHECK(h.app.bridge().mcAlive());
	auto got = h.mc.recv();
	const Msg* hello = find(got, ocp::kMsgHello);
	CHECK(hello != nullptr);
	if (hello) {
		const auto hh = hello->as<ocp::Hello>();
		CHECK_EQ(hh.version, ocp::kVersion);
		CHECK(hh.gta_epoch != 0);
		CHECK_NEAR(hh.view_radius_h, 28.0, 1e-6);
	}
	CHECK_EQ(ocp::load_acquire(h.mc.L.hdr->gta_epoch), h.app.bridge().gtaEpoch());

	h.mc.session(0.5, 64.0, 0.5);
	h.mc.palette(1, ocp::kPalOpaque | ocp::kPalPlaceable, 0x7F7F7F, "minecraft:stone");
	h.frames(2);
	CHECK(h.app.sync().session().active);
	CHECK_EQ(h.app.palette().size(), 1u);
	CHECK(!h.app.transform().valid);  // streaming starts only after the player pins the world

	h.app.onKey(0x78);
	h.frames(2);
	CHECK(h.app.transform().valid);
	got = h.mc.recv();
	const Msg* anchor = find(got, ocp::kMsgAnchor);
	CHECK(anchor != nullptr);
	if (anchor) {
		const auto a = anchor->as<ocp::Anchor>();
		CHECK_NEAR(a.gx, 1000, 1e-9);
		CHECK_NEAR(a.gy, 2000, 1e-9);
		CHECK_NEAR(a.gz, 30, 1e-9);
	}
}

TEST(app_publishes_player_state_to_minecraft_every_frame) {
	Harness h("state");
	h.game.player.velocity = {3, 4, 0};
	h.game.player.weapon = 0xDEADBEEF;
	h.frames(3);
	ocp::PlayerState s{};
	CHECK(ocp::seq_read(*h.mc.L.gta_state, s));
	CHECK_NEAR(s.px, 1000, 1e-9);
	CHECK_NEAR(s.vy, 4, 1e-6);
	CHECK_NEAR(s.hit_max[2], 1.8, 1e-6);
	CHECK_NEAR(s.ay, 0.83205, 1e-4);
	CHECK_EQ(s.weapon_hash, 0xDEADBEEFu);
	CHECK(s.flags & ocp::kPfOnFoot);
	CHECK(!(s.flags & ocp::kPfAnchored));
	CHECK(ocp::load_acquire(h.mc.L.hdr->gta_heartbeat) >= 3);
	const uint64_t f1 = s.frame;
	h.frames(5);
	CHECK(ocp::seq_read(*h.mc.L.gta_state, s));
	CHECK(s.frame >= f1 + 5);
}

TEST(app_renders_streamed_voxels_as_props_at_the_mapped_position) {
	Harness h("render");
	h.connectAndAnchor();
	CHECK(h.app.transform().valid);
	CHECK(h.game.activeProps() > 100);
	// Voxel (0,63,0) is the block directly under the MC anchor point (0.5, 64, 0.5) = GTA (1000, 2000, 30).
	const Handle hd = h.app.props().handleAt(0, 63, 0);
	CHECK(hd != 0);
	if (hd) {
		const Vec3 p = h.game.props.at(hd).pos;
		CHECK_NEAR(p.x, 1000.0, 1e-9);   // centre of voxel x in [0,1) -> 1000 (anchor is its centre)
		CHECK_NEAR(p.y, 2000.0, 1e-9);
		CHECK_NEAR(p.z, 29.5, 1e-9);     // block top is at the feet plane, so its centre is half a metre below
	}
	// +X east and +Z south: voxel (3, 63, 2) lies 3 m east and 2 m south (GTA -Y) of the anchor.
	const Handle h2 = h.app.props().handleAt(3, 63, 2);
	CHECK(h2 != 0);
	if (h2) {
		const Vec3 p = h.game.props.at(h2).pos;
		CHECK_NEAR(p.x, 1003.0, 1e-9);
		CHECK_NEAR(p.y, 1998.0, 1e-9);
	}
}

TEST(app_bullet_impact_on_a_prop_sends_break_request_with_face_and_damage) {
	Harness h("shot");
	h.connectAndAnchor();
	h.mc.recv();  // discard handshake traffic
	// Aim from above and in front at the top face of voxel (2, 63, -3).
	const Vec3 centre = h.app.transform().voxelCenterGta(2, 63, -3);
	const Vec3 impact = centre + Vec3{0, 0, 0.5};
	h.game.player.camPos = impact + Vec3{0, -5, 5};
	h.game.player.camDir = normalized(impact - h.game.player.camPos);
	h.game.player.shooting = true;
	h.game.player.hasImpact = true;
	h.game.player.impact = impact;
	h.game.player.weapon = 0x1234;
	h.game.damagePerWeapon = 26.0f;
	h.frames(1);
	auto got = h.mc.recv();
	const Msg* br = find(got, ocp::kMsgBreakRequest);
	CHECK(br != nullptr);
	if (br) {
		const auto b = br->as<ocp::BreakRequest>();
		CHECK_EQ(b.source, ocp::kSrcBullet);
		CHECK_EQ(b.x, 2);
		CHECK_EQ(b.y, 63);
		CHECK_EQ(b.z, -3);
		CHECK_EQ(b.face, ocp::kUp);
		CHECK_NEAR(b.damage, 26.0, 1e-6);
		CHECK_EQ(b.weapon_hash, 0x1234u);
		CHECK(b.seq != 0);
	}
	CHECK_EQ(h.app.interaction().counters().breaksSent, 1u);
}

TEST(app_shots_that_hit_something_else_or_miss_do_not_break_blocks) {
	Harness h("shotmiss");
	h.connectAndAnchor();
	h.mc.recv();
	// A non-voxel entity (say a car) in front of the plate: the probe strikes it first.
	const Vec3 centre = h.app.transform().voxelCenterGta(2, 63, -3);
	const Vec3 impact = centre + Vec3{0, 0, 0.5};
	h.game.player.camPos = impact + Vec3{0, -5, 5};
	h.game.player.camDir = normalized(impact - h.game.player.camPos);
	const Vec3 mid = h.game.player.camPos + (impact - h.game.player.camPos) * 0.5;
	h.game.props[999999] = {0xC0FFEE, mid, true, -1};  // foreign entity, not tracked by the prop manager
	h.game.player.shooting = true;
	h.game.player.hasImpact = true;
	h.game.player.impact = impact;
	h.frames(2);
	CHECK(find(h.mc.recv(), ocp::kMsgBreakRequest) == nullptr);
	h.game.props.erase(999999);

	h.game.player.hasImpact = false;  // fired into the sky: no impact point
	h.frames(2);
	CHECK(find(h.mc.recv(), ocp::kMsgBreakRequest) == nullptr);
}

TEST(app_shots_from_inside_a_vehicle_ignore_that_vehicle_not_just_the_ped) {
	Harness h("carshot");
	h.connectAndAnchor();
	h.mc.recv();
	const Vec3 impact = h.app.transform().voxelCenterGta(2, 63, -3) + Vec3{0, 0, 0.5};
	h.game.player.camPos = impact + Vec3{0, -5, 5};
	h.game.player.camDir = normalized(impact - h.game.player.camPos);
	h.game.player.shooting = true;
	h.game.player.hasImpact = true;
	h.game.player.impact = impact;
	// The player's own car sits between the camera and the target, exactly as a driver-seat camera would see it.
	h.game.player.vehicle = 55;
	h.game.player.flags = ocp::kPfInVehicle;
	h.game.props[55] = {0xC0FFEE, h.game.player.camPos + (impact - h.game.player.camPos) * 0.3, true, -1};
	h.frames(1);
	const auto got = h.mc.recv();
	CHECK(find(got, ocp::kMsgBreakRequest) != nullptr);  // the probe skipped vehicle 55 and reached the voxel behind it
	// Control: the same car on foot (a different car, not ignored) does block the shot.
	h.game.player.vehicle = 0;
	h.game.player.flags = ocp::kPfOnFoot;
	h.frames(3);
	CHECK(find(h.mc.recv(), ocp::kMsgBreakRequest) == nullptr);
}

TEST(app_explosive_weapons_report_explosive_source) {
	Harness h("rpg");
	h.connectAndAnchor();
	h.mc.recv();
	const Vec3 impact = h.app.transform().voxelCenterGta(-2, 63, 1) + Vec3{0, 0, 0.5};
	h.game.player.camPos = impact + Vec3{0, -5, 5};
	h.game.player.camDir = normalized(impact - h.game.player.camPos);
	h.game.player.shooting = true;
	h.game.player.hasImpact = true;
	h.game.player.impact = impact;
	h.game.player.weapon = h.game.joaat("WEAPON_RPG");
	h.frames(1);
	const auto got = h.mc.recv();
	const Msg* br = find(got, ocp::kMsgBreakRequest);
	CHECK(br != nullptr);
	if (br) CHECK_EQ(br->as<ocp::BreakRequest>().source, ocp::kSrcExplosive);
}

TEST(app_mine_and_place_hotkeys_use_the_crosshair_voxel_and_face) {
	Harness h("tools");
	h.connectAndAnchor();
	h.mc.recv();
	const Vec3 target = h.app.transform().voxelCenterGta(1, 63, 1);
	// Look at the centre of the voxel's top face (0.5 m above its centre) along a 45 degree descent.
	h.game.player.camPos = target + Vec3{0, -3, 3.5};
	h.game.player.camDir = normalized(Vec3{0, 3, -3});
	h.frames(1);
	h.app.onKey(0x76);  // F7 mine
	h.app.onKey(0xDD);  // ] select first placeable block
	h.app.onKey(0x75);  // F6 place
	h.frames(1);
	auto got = h.mc.recv();
	const Msg* br = find(got, ocp::kMsgBreakRequest);
	const Msg* pr = find(got, ocp::kMsgPlaceRequest);
	CHECK(br != nullptr && pr != nullptr);
	if (br) {
		const auto b = br->as<ocp::BreakRequest>();
		CHECK_EQ(b.source, ocp::kSrcTool);
		CHECK(b.x == 1 && b.y == 63 && b.z == 1);
		CHECK_EQ(b.face, ocp::kUp);
	}
	if (pr) {
		const auto p = pr->as<ocp::PlaceRequest>();
		CHECK_EQ(p.palette_id, 1);  // first placeable id is 1 (stone); ] cycled from "none" to it
		CHECK(p.x == 1 && p.y == 63 && p.z == 1);
		CHECK_EQ(p.face, ocp::kUp);
	}
	h.app.onKey(0xDD);
	CHECK_EQ(h.app.selectedBlock(), 2);
	h.app.onKey(0xDB);
	CHECK_EQ(h.app.selectedBlock(), 1);
}

TEST(app_applies_minecraft_block_changes_to_props_immediately) {
	Harness h("update");
	h.connectAndAnchor();
	const size_t before = h.game.activeProps();
	h.mc.update(1, 63, 1, 0, ocp::kUpdFromGta);  // broken
	h.mc.update(1, 64, 1, 2, ocp::kUpdFromGta);  // placed on top
	h.frames(1);
	CHECK(!h.app.props().isLive(1, 63, 1));
	CHECK(h.app.props().isLive(1, 64, 1));
	CHECK_EQ(h.game.activeProps(), before);  // one removed, one added, same frame
	ocp::Ack ack{42, ocp::kAckBroken, 0, 1, 63, 1, 1.0f};
	h.mc.send(ocp::kMsgAck, ack);
	h.frames(1);
	CHECK(h.app.statusLine().find("LIVE") != std::string::npos);
}

TEST(app_vehicle_impact_into_voxels_reports_collision_with_contact_voxel) {
	Harness h("crash");
	h.connectAndAnchor(10);
	h.mc.recv();
	// A car at the plate's edge driving south (-Y in GTA = +Z in MC) into the voxel row at z = 6.
	PlayerSample& p = h.game.player;
	p.vehicle = 55;
	p.flags = ocp::kPfInVehicle;
	const Vec3 voxelC = h.app.transform().voxelCenterGta(0, 63, 6);
	p.feet = voxelC + Vec3{0, 3.1, 0.5};  // box centre ~3.1 m north of the voxel, resting on top of the plate
	p.hitMin[0] = p.hitMin[1] = -1.0f;
	p.hitMax[0] = p.hitMax[1] = 1.0f;
	p.hitMin[2] = 0;
	p.hitMax[2] = 1.5f;
	p.velocity = {0, -20, 0};
	h.frames(1);  // establishes the previous-frame speed
	p.feet = voxelC + Vec3{0, 1.6, 0.5};  // now pressed against the voxel row
	p.velocity = {0, -2, 0};              // lost 18 m/s in one frame
	h.frames(1);
	const auto got = h.mc.recv();
	const Msg* ce = find(got, ocp::kMsgCollision);
	CHECK(ce != nullptr);
	if (ce) {
		const auto c = ce->as<ocp::CollisionEvent>();
		CHECK_EQ(c.kind, ocp::kColVehicle);
		CHECK_NEAR(c.speed, 20.0, 1e-3);
		CHECK_NEAR(c.delta_v, 18.0, 1e-3);
		CHECK(c.z >= 5 && c.z <= 7);
		CHECK_EQ(c.nz, -1);  // normal points from the voxel back toward the car (north = -Z)
		CHECK(c.mass > 0);
	}
	// The same crash sustained next frame is rate limited, not re-reported.
	h.frames(1);
	CHECK(find(h.mc.recv(), ocp::kMsgCollision) == nullptr);
}

TEST(app_survives_minecraft_restart_and_reanchors_to_the_same_gta_point) {
	Harness h("restart");
	h.connectAndAnchor();
	CHECK(h.app.transform().valid);
	h.mc.recv();
	// Minecraft restarts: new epoch, brand-new world announced with a different spawn.
	ocp::store_release(h.mc.L.hdr->mc_epoch, 0x1111222233334444ull);
	h.frames(2);
	h.mc.session(10.5, 70.0, -4.5);
	h.mc.palette(1, ocp::kPalOpaque, 0x7F7F7F, "minecraft:stone");
	h.frames(2);
	CHECK(h.app.sync().session().active);
	CHECK_NEAR(h.app.sync().session().anchorMc.x, 10.5, 1e-9);
	CHECK(h.app.transform().valid);
	// The player's pin is preserved: the new MC anchor maps onto the old GTA point, and the host is told.
	CHECK_NEAR(h.app.transform().anchorGta.x, 1000, 1e-9);
	CHECK_NEAR(h.app.transform().anchorMc.x, 10.5, 1e-9);
	auto got = h.mc.recv();
	CHECK(find(got, ocp::kMsgHello) != nullptr);
	CHECK(find(got, ocp::kMsgAnchor) != nullptr);
}

TEST(app_stays_inert_in_gta_online_and_cleans_up) {
	Harness h("online");
	h.connectAndAnchor();
	CHECK(h.game.props.size() > 0);
	h.game.online = true;
	h.frames(2);
	CHECK_EQ(h.game.props.size(), 0u);
	CHECK(!h.app.bridge().attached());
	CHECK_EQ(ocp::load_acquire(h.mc.L.hdr->gta_epoch), 0ull);  // peer sees us leave
}

TEST(app_keeps_world_visible_when_minecraft_goes_quiet_then_recovers) {
	Harness h("quiet");
	h.connectAndAnchor();
	const size_t props = h.game.props.size();
	for (int i = 0; i < 150; ++i) {  // 2.5 s with no heartbeat
		h.game.clockUs += 16'667;
		h.app.frame();
	}
	CHECK(!h.app.bridge().mcAlive());
	CHECK_EQ(h.game.props.size(), props);  // nothing flickers away just because the server is paused
	h.frames(3);
	CHECK(h.app.bridge().mcAlive());
}

TEST(model_calibrator_picks_the_closest_to_unit_cube) {
	tst::MockGame game;
	for (const char* n : {"big_box", "unit_ish", "tiny"}) game.validModels.insert(game.joaat(n));
	game.dims[game.joaat("big_box")] = {true, {-1, -1, 0}, {1, 1, 2}};
	game.dims[game.joaat("unit_ish")] = {true, {-0.5, -0.5, 0}, {0.52, 0.5, 1.0}};
	game.dims[game.joaat("tiny")] = {true, {-0.1, -0.1, 0}, {0.1, 0.1, 0.2}};
	ModelCalibrator c;
	c.start({"missing_model", "big_box", "unit_ish", "tiny"});
	for (int i = 0; i < 30 && c.running(); ++i) {
		game.clockUs += 16'667;
		c.step(game, game.clockUs);
	}
	CHECK(!c.running());
	CHECK(c.found());
	CHECK(c.bestName() == "unit_ish");
	CHECK(c.bestError() < 0.05);
}

TEST(config_parses_ini_with_comments_hex_keys_and_palette_overrides) {
	Config c;
	std::string warn;
	CHECK(parseConfigText(
		"; comment\n[render]\nmodel = prop_foo   # trailing\nmax_props = 900\nspawn_radius=18.5\n"
		"model_candidates = a, b ,c\n[keys]\nanchor = 0x70\n[world]\nauto_anchor = yes\n"
		"[palette]\nminecraft:glass = prop_glass, 3\nminecraft:Stone = prop_rock\n[bogus]\nx=1\n",
		c, &warn));
	CHECK(c.model == "prop_foo");
	CHECK_EQ(c.props.maxProps, 900u);
	CHECK_NEAR(c.props.spawnRadius, 18.5, 1e-6);
	CHECK_EQ(c.keyAnchor, 0x70);
	CHECK(c.autoAnchor);
	CHECK((c.modelCandidates == std::vector<std::string>{"a", "b", "c"}));
	CHECK_EQ(c.paletteOverrides.size(), 2u);
	CHECK(c.paletteOverrides[0].first == "minecraft:glass" && c.paletteOverrides[0].second.second == 3);
	CHECK(c.paletteOverrides[1].first == "minecraft:Stone" && c.paletteOverrides[1].second.second == -1);
	CHECK(warn.find("bogus") != std::string::npos);
}

TEST(shipped_ini_parses_without_warnings_and_equals_the_code_defaults) {
	// The README promises that OrbitalPassthrough.ini lists the defaults; hold it to that.
	std::string path = __FILE__;
	path = path.substr(0, path.find_last_of('/')) + "/../OrbitalPassthrough.ini";
	Config fromFile, defaults;
	std::string warnings;
	CHECK(loadConfigFile(path, fromFile, &warnings));
	CHECK(warnings.empty());
	if (!warnings.empty()) std::printf("%s", warnings.c_str());
	CHECK(fromFile.shmName == defaults.shmName);
	CHECK(fromFile.model == defaults.model);
	CHECK(fromFile.modelCandidates == defaults.modelCandidates);
	CHECK_EQ(fromFile.props.maxProps, defaults.props.maxProps);
	CHECK_NEAR(fromFile.props.spawnRadius, defaults.props.spawnRadius, 1e-6);
	CHECK_NEAR(fromFile.props.retireMargin, defaults.props.retireMargin, 1e-6);
	CHECK_EQ(fromFile.props.maxSpawnsPerFrame, defaults.props.maxSpawnsPerFrame);
	CHECK_EQ(fromFile.props.maxRetiresPerFrame, defaults.props.maxRetiresPerFrame);
	CHECK_EQ(fromFile.props.frameBudgetUs, defaults.props.frameBudgetUs);
	CHECK_EQ(fromFile.colourBuckets, defaults.colourBuckets);
	CHECK_EQ(fromFile.autoAnchor, defaults.autoAnchor);
	CHECK_NEAR(fromFile.anchorZOffset, defaults.anchorZOffset, 1e-6);
	CHECK_NEAR(fromFile.viewRadiusH, defaults.viewRadiusH, 1e-6);
	CHECK_NEAR(fromFile.viewRadiusV, defaults.viewRadiusV, 1e-6);
	CHECK_NEAR(fromFile.inter.reach, defaults.inter.reach, 1e-6);
	CHECK_NEAR(fromFile.inter.toolDamage, defaults.inter.toolDamage, 1e-6);
	CHECK_NEAR(fromFile.inter.explosiveDamage, defaults.inter.explosiveDamage, 1e-6);
	CHECK_NEAR(fromFile.inter.bulletRange, defaults.inter.bulletRange, 1e-6);
	CHECK_NEAR(fromFile.inter.collisionMinDeltaV, defaults.inter.collisionMinDeltaV, 1e-6);
	CHECK_NEAR(fromFile.inter.vehicleMassKg, defaults.inter.vehicleMassKg, 1e-6);
	CHECK_EQ(fromFile.keyAnchor, defaults.keyAnchor);
	CHECK_EQ(fromFile.keyOverlay, defaults.keyOverlay);
	CHECK_EQ(fromFile.keyMine, defaults.keyMine);
	CHECK_EQ(fromFile.keyPlace, defaults.keyPlace);
	CHECK_EQ(fromFile.keyCalibrate, defaults.keyCalibrate);
	CHECK_EQ(fromFile.keyPrevBlock, defaults.keyPrevBlock);
	CHECK_EQ(fromFile.keyNextBlock, defaults.keyNextBlock);
	CHECK_EQ(fromFile.overlay, defaults.overlay);
	CHECK(fromFile.paletteOverrides.empty());
}

TEST(voxel_raycast_matches_brute_force_and_reports_entry_face) {
	VoxelStore s;
	std::mt19937 rng(5);
	for (int i = 0; i < 400; ++i) s.set(static_cast<int>(rng() % 21) - 10, static_cast<int>(rng() % 21) - 10, static_cast<int>(rng() % 21) - 10, 1, false);
	std::uniform_real_distribution<double> u(-1, 1), p(-9, 9);
	int checked = 0, grazing = 0;
	for (int i = 0; i < 3000; ++i) {
		const Vec3 o{p(rng), p(rng), p(rng)};
		const Vec3 d = normalized(Vec3{u(rng), u(rng), u(rng)});
		if (length(d) < 0.5 || s.get(Transform::floorToInt(o.x), Transform::floorToInt(o.y), Transform::floorToInt(o.z)) != 0) continue;
		const VoxelHit hit = raycastVoxels(s, o, d, 30.0);
		// Brute force: march in tiny steps.
		VoxelHit ref;
		for (double t = 0; t <= 30.0; t += 0.001) {
			const Vec3 q = o + d * t;
			if (s.get(Transform::floorToInt(q.x), Transform::floorToInt(q.y), Transform::floorToInt(q.z)) != 0) {
				ref.hit = true;
				ref.x = Transform::floorToInt(q.x);
				ref.y = Transform::floorToInt(q.y);
				ref.z = Transform::floorToInt(q.z);
				ref.t = t;
				break;
			}
		}
		if (hit.hit != ref.hit) {
			// The brute force can miss a corner clip thinner than its step; tolerate only genuinely grazing cases.
			if (std::fabs(hit.t - ref.t) > 0.01 && hit.hit) continue;
		}
		if (hit.hit) {
			// Every DDA hit must be a voxel the ray genuinely passes through. A sliver shorter than the brute-force
			// step is a corner graze the 1 mm march can legitimately miss, so those rays are excluded, not "passed".
			const double lo[3] = {double(hit.x), double(hit.y), double(hit.z)};
			const double oo[3] = {o.x, o.y, o.z}, dd[3] = {d.x, d.y, d.z};
			double t0 = 0, t1 = 1e9;
			for (int a = 0; a < 3; ++a) {
				if (std::fabs(dd[a]) < 1e-12) continue;
				double ta = (lo[a] - oo[a]) / dd[a], tb = (lo[a] + 1 - oo[a]) / dd[a];
				if (ta > tb) std::swap(ta, tb);
				t0 = std::max(t0, ta);
				t1 = std::min(t1, tb);
			}
			CHECK(t1 > t0 - 1e-9);                 // the ray really intersects the voxel DDA reported
			CHECK_NEAR(t0, hit.t, 1e-6);           // and enters it exactly at the reported distance
			if (t1 - t0 < 0.004) { ++grazing; continue; }
		}
		CHECK_EQ(hit.hit, ref.hit);
		if (hit.hit && ref.hit) {
			CHECK(hit.x == ref.x && hit.y == ref.y && hit.z == ref.z);
			CHECK_NEAR(hit.t, ref.t, 0.0015);
			int dx, dy, dz;
			Transform::faceNormal(hit.face, dx, dy, dz);
			CHECK(dot(Vec3{double(dx), double(dy), double(dz)}, d) < 0);  // entered through a face that opposes the ray
		}
		++checked;
	}
	CHECK(checked > 1000);
	CHECK(grazing < 40);  // grazes are rare; if this grows the DDA is mis-stepping, not clipping corners
}

int main(int argc, char** argv) { return tst::runAll(argc > 1 ? argv[1] : nullptr); }
