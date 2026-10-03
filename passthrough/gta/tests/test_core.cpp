// Unit tests for the portable core: protocol rings/seqlocks, containers, transform, voxel store, palette and the
// prop manager. Run on any platform; no GTA V needed.
#include <atomic>
#include <chrono>
#include <cmath>
#include <random>
#include <thread>
#include <unordered_map>

#include "mock_game.h"
#include "test_util.h"
#include "../src/core/flat_map.h"
#include "../src/core/palette.h"
#include "../src/core/prop_manager.h"
#include "../src/core/transform.h"
#include "../src/core/voxel_store.h"

using namespace opt;

// ---------------------------------------------------------------------------------------------------------------
// Containers and keys
// ---------------------------------------------------------------------------------------------------------------
TEST(flatmap_matches_reference_under_random_ops) {
	FlatMap64<uint32_t> m(8);
	std::unordered_map<uint64_t, uint32_t> ref;
	std::mt19937_64 rng(12345);
	for (int i = 0; i < 400000; ++i) {
		const uint64_t k = rng() % 3000;  // dense key space forces collisions and many erase/reinsert cycles
		switch (rng() % 3) {
			case 0: {
				const uint32_t v = static_cast<uint32_t>(rng());
				CHECK_EQ(m.put(k, v), ref.find(k) == ref.end());
				ref[k] = v;
				break;
			}
			case 1:
				CHECK_EQ(m.erase(k), ref.erase(k) == 1);
				break;
			default: {
				const uint32_t* f = m.find(k);
				auto it = ref.find(k);
				CHECK_EQ(f != nullptr, it != ref.end());
				if (f && it != ref.end()) CHECK_EQ(*f, it->second);
			}
		}
	}
	CHECK_EQ(m.size(), ref.size());
	size_t seen = 0;
	m.forEach([&](uint64_t k, uint32_t v) {
		++seen;
		CHECK(ref.count(k) && ref[k] == v);
	});
	CHECK_EQ(seen, ref.size());
}

TEST(voxel_key_roundtrip_including_negative_and_extremes) {
	const int32_t xs[] = {0, 1, -1, 15, -16, 29999999, -30000000, 12345, -98765};
	const int32_t ys[] = {0, 1, -1, -64, 319, 2047, -2048};
	for (int32_t x : xs)
		for (int32_t y : ys)
			for (int32_t z : xs) {
				int32_t rx, ry, rz;
				unpackVoxelKey(voxelKey(x, y, z), rx, ry, rz);
				CHECK(rx == x && ry == y && rz == z);
			}
	// Distinct voxels must never collide, including the all-ones corner.
	CHECK(voxelKey(-1, -1, -1) != voxelKey(0, 0, 0));
	CHECK(voxelKey(1, 0, 0) != voxelKey(0, 0, 1));
}

TEST(transform_is_a_proper_rotation_and_roundtrips) {
	Transform t;
	t.valid = true;
	t.anchorMc = {100.5, 64, -200.5};
	t.anchorGta = {-1234.0, 5678.0, 31.25};
	// Anchor maps to anchor.
	const Vec3 a = t.toGta(t.anchorMc);
	CHECK_NEAR(a.x, t.anchorGta.x, 1e-9);
	CHECK_NEAR(a.y, t.anchorGta.y, 1e-9);
	CHECK_NEAR(a.z, t.anchorGta.z, 1e-9);
	// East stays east, up stays up, Minecraft south (+Z) is GTA south (-Y).
	const Vec3 e = t.toGta(t.anchorMc + Vec3{1, 0, 0}) - t.anchorGta;
	const Vec3 u = t.toGta(t.anchorMc + Vec3{0, 1, 0}) - t.anchorGta;
	const Vec3 s = t.toGta(t.anchorMc + Vec3{0, 0, 1}) - t.anchorGta;
	CHECK_NEAR(e.x, 1, 1e-9);
	CHECK_NEAR(u.z, 1, 1e-9);
	CHECK_NEAR(s.y, -1, 1e-9);
	// Determinant of the linear part is +1: no mirroring.
	const double det = e.x * (u.y * s.z - u.z * s.y) - e.y * (u.x * s.z - u.z * s.x) + e.z * (u.x * s.y - u.y * s.x);
	CHECK_NEAR(det, 1.0, 1e-9);
	// Round trip on random points.
	std::mt19937 rng(7);
	std::uniform_real_distribution<double> d(-5000, 5000);
	for (int i = 0; i < 1000; ++i) {
		const Vec3 p{d(rng), d(rng), d(rng)};
		const Vec3 q = t.toMc(t.toGta(p));
		CHECK_NEAR(q.x, p.x, 1e-6);
		CHECK_NEAR(q.y, p.y, 1e-6);
		CHECK_NEAR(q.z, p.z, 1e-6);
	}
	// Normals: GTA up -> Minecraft Up, GTA north (+Y) -> Minecraft North (-Z).
	CHECK_EQ(Transform::faceFromGtaNormal({0, 0, 1}), ocp::kUp);
	CHECK_EQ(Transform::faceFromGtaNormal({0, 0, -1}), ocp::kDown);
	CHECK_EQ(Transform::faceFromGtaNormal({0, 1, 0}), ocp::kNorth);
	CHECK_EQ(Transform::faceFromGtaNormal({0, -1, 0}), ocp::kSouth);
	CHECK_EQ(Transform::faceFromGtaNormal({1, 0, 0}), ocp::kEast);
	CHECK_EQ(Transform::faceFromGtaNormal({-1, 0, 0}), ocp::kWest);
	for (uint8_t f = 0; f < 6; ++f) {  // face -> normal -> face is the identity
		int dx, dy, dz;
		Transform::faceNormal(f, dx, dy, dz);
		const Vec3 g = Transform::dirToGta({double(dx), double(dy), double(dz)});
		CHECK_EQ(Transform::faceFromGtaNormal(g), f);
	}
}

// ---------------------------------------------------------------------------------------------------------------
// Protocol: rings and seqlocks
// ---------------------------------------------------------------------------------------------------------------
namespace {
struct RingMem {
	std::vector<uint8_t> ctlBytes, data;
	ocp::RingControl* ctl;
	explicit RingMem(uint32_t cap) : ctlBytes(sizeof(ocp::RingControl) + 64), data(cap) {
		ctl = reinterpret_cast<ocp::RingControl*>((reinterpret_cast<uintptr_t>(ctlBytes.data()) + 63) & ~uintptr_t{63});
		std::memset(ctl, 0, sizeof *ctl);
		ctl->capacity = cap;
		ctl->mask = cap - 1;
	}
};
}  // namespace

TEST(ring_wraps_pads_and_reports_full) {
	RingMem m(1024);
	ocp::RingWriter w(m.ctl, m.data.data());
	ocp::RingReader r(m.ctl, m.data.data());
	uint32_t written = 0, got = 0, nextExpected = 0;
	std::vector<uint8_t> payload(200);
	// Fill until full.
	for (;; ++written) {
		std::memset(payload.data(), static_cast<int>(written & 0xFF), payload.size());
		uint8_t* p = w.begin(100, 37 + (written % 5) * 8);
		if (!p) break;
		std::memcpy(p, payload.data(), 37 + (written % 5) * 8);
		w.commit();
	}
	CHECK(written >= 3);
	CHECK(m.ctl->dropped >= 1);
	// Drain everything; interleave more writes so the wrap point is crossed many times.
	for (int round = 0; round < 500; ++round) {
		got += r.drain([&](uint16_t type, const uint8_t* p, uint32_t len) {
			CHECK_EQ(type, 100);
			CHECK_EQ(len, 37u + (nextExpected % 5) * 8);
			for (uint32_t i = 0; i < len; ++i) {
				if (p[i] != (nextExpected & 0xFF)) {
					CHECK(false);
					break;
				}
			}
			++nextExpected;
		});
		for (int k = 0; k < 3; ++k) {
			std::memset(payload.data(), static_cast<int>(written & 0xFF), payload.size());
			uint8_t* p = w.begin(100, 37 + (written % 5) * 8);
			if (!p) break;
			std::memcpy(p, payload.data(), 37 + (written % 5) * 8);
			w.commit();
			++written;
		}
	}
	got += r.drain([&](uint16_t, const uint8_t*, uint32_t) { ++nextExpected; });
	CHECK_EQ(got, written);
	CHECK_EQ(r.corrupt_count(), 0u);
}

TEST(ring_uncommitted_records_are_invisible) {
	RingMem m(4096);
	ocp::RingWriter w(m.ctl, m.data.data());
	ocp::RingReader r(m.ctl, m.data.data());
	uint8_t* p = w.begin(7, 8);
	CHECK(p != nullptr);
	std::memset(p, 0xAB, 8);
	CHECK_EQ(r.drain([](uint16_t, const uint8_t*, uint32_t) {}), 0u);
	w.commit();
	CHECK_EQ(r.drain([](uint16_t, const uint8_t*, uint32_t) {}), 1u);
}

TEST(ring_rejects_oversize_and_resyncs_after_corruption) {
	RingMem m(4096);
	ocp::RingWriter w(m.ctl, m.data.data());
	ocp::RingReader r(m.ctl, m.data.data());
	CHECK(w.begin(1, ocp::kMaxPayload + 1) == nullptr);
	CHECK(w.write(1, "abcdefgh", 8));
	// A hostile peer scribbles an absurd length into the next record header and publishes it.
	ocp::MsgHeader evil{1, 0, 0xFFFFFFF0u};
	std::memcpy(m.data.data() + 16, &evil, sizeof evil);
	ocp::store_release(m.ctl->head, 16 + 24);
	int delivered = 0;
	r.drain([&](uint16_t, const uint8_t*, uint32_t) { ++delivered; });
	CHECK_EQ(delivered, 1);
	CHECK_EQ(r.corrupt_count(), 1u);
	CHECK_EQ(r.available(), 0ull);  // resynchronised to head; the next legitimate write is delivered
	ocp::RingWriter w2(m.ctl, m.data.data());
	CHECK(w2.write(2, "12345678", 8));
	delivered = 0;
	r.drain([&](uint16_t t, const uint8_t*, uint32_t) { delivered += (t == 2); });
	CHECK_EQ(delivered, 1);
}

TEST(ring_two_thread_stress_preserves_order_and_content) {
	RingMem m(1u << 16);
	constexpr uint64_t N = 2'000'000;
	std::atomic<bool> bad{false};
	std::thread producer([&] {
		ocp::RingWriter w(m.ctl, m.data.data());
		std::mt19937 rng(99);
		for (uint64_t i = 0; i < N;) {
			const uint32_t len = 8 + (rng() % 40) * 8;
			uint8_t* p = w.begin(static_cast<uint16_t>(1 + (i % 3)), len);
			if (!p) {
				std::this_thread::yield();
				continue;
			}
			std::memcpy(p, &i, 8);
			for (uint32_t k = 8; k < len; ++k) p[k] = static_cast<uint8_t>(i + k);
			if ((i & 7) == 7) w.commit();  // batch commits like the real producers do
			++i;
			if (i == N) w.commit();
		}
		w.commit();
	});
	std::thread consumer([&] {
		ocp::RingReader r(m.ctl, m.data.data());
		uint64_t expect = 0;
		while (expect < N && !bad) {
			r.drain([&](uint16_t type, const uint8_t* p, uint32_t len) {
				uint64_t seq;
				std::memcpy(&seq, p, 8);
				if (seq != expect || type != 1 + (seq % 3)) bad = true;
				for (uint32_t k = 8; k < len; ++k) {
					if (p[k] != static_cast<uint8_t>(seq + k)) bad = true;
				}
				++expect;
			});
		}
		if (expect != N) bad = true;
	});
	producer.join();
	consumer.join();
	CHECK(!bad.load());
}

TEST(seqlock_never_yields_torn_state) {
	alignas(64) static ocp::SeqSlot<ocp::PlayerState> slot;
	std::memset(&slot, 0, sizeof slot);
	std::atomic<bool> stop{false};
	std::thread writer([&] {
		ocp::PlayerState s{};
		for (uint64_t i = 1; !stop; ++i) {
			s.frame = i;
			s.px = s.py = s.pz = static_cast<double>(i);
			s.vx = s.vy = s.vz = static_cast<float>(i % 100000);
			s.weapon_hash = static_cast<uint32_t>(i);
			ocp::seq_write(slot, s);
		}
	});
	uint64_t reads = 0, torn = 0, last = 0, backwards = 0;
	const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(600);
	while (std::chrono::steady_clock::now() < end) {
		ocp::PlayerState s{};
		if (!ocp::seq_read(slot, s)) continue;
		++reads;
		if (s.px != static_cast<double>(s.frame) || s.pz != static_cast<double>(s.frame) ||
		    s.weapon_hash != static_cast<uint32_t>(s.frame) || s.vy != static_cast<float>(s.frame % 100000)) {
			++torn;
		}
		if (s.frame < last) ++backwards;
		last = s.frame;
	}
	stop = true;
	writer.join();
	CHECK(reads > 1000);
	CHECK_EQ(torn, 0ull);
	CHECK_EQ(backwards, 0ull);
}

TEST(layout_init_is_race_free_and_validated) {
	std::vector<uint8_t> raw(ocp::kTotalSize + 4096, 0);
	void* base = reinterpret_cast<void*>((reinterpret_cast<uintptr_t>(raw.data()) + 4095) & ~uintptr_t{4095});
	ocp::Layout l = ocp::Layout::over(base);
	std::atomic<int> ok{0};
	std::vector<std::thread> ts;
	for (int i = 0; i < 8; ++i) {
		ts.emplace_back([&] { ok += ocp::init_or_validate(l) == ocp::AttachResult::kOk; });
	}
	for (auto& t : ts) t.join();
	CHECK_EQ(ok.load(), 8);
	CHECK_EQ(l.ring_a_ctl->capacity, ocp::kRingACap);
	CHECK_EQ(l.ring_b_ctl->mask, ocp::kRingBCap - 1);
	l.hdr->version = 99;
	CHECK(ocp::init_or_validate(l) == ocp::AttachResult::kBadVersion);
}

// ---------------------------------------------------------------------------------------------------------------
// Voxel store and palette
// ---------------------------------------------------------------------------------------------------------------
namespace {
std::vector<uint8_t> sectionMsg(int sx, int sy, int sz, uint16_t flags, std::vector<std::pair<uint16_t, uint16_t>> entries) {
	ocp::SectionDataHdr h{};
	h.sx = sx;
	h.sy = sy;
	h.sz = sz;
	h.count = static_cast<uint16_t>(entries.size());
	h.flags = flags;
	std::vector<uint8_t> out(sizeof h + entries.size() * sizeof(ocp::VoxelEntry));
	std::memcpy(out.data(), &h, sizeof h);
	for (size_t i = 0; i < entries.size(); ++i) {
		ocp::VoxelEntry e{entries[i].first, entries[i].second};
		std::memcpy(out.data() + sizeof h + i * sizeof e, &e, sizeof e);
	}
	return out;
}
}  // namespace

TEST(voxel_store_sections_updates_and_change_log) {
	VoxelStore s;
	// local = x | z<<4 | y<<8: (1,2,3) in section (-1,0,2)
	const uint16_t local = 1 | (3 << 4) | (2 << 8);
	auto m = sectionMsg(-1, 0, 2, ocp::kSecReplace, {{local, 5}, {0, 6}});
	CHECK(s.applySectionData(m.data(), static_cast<uint32_t>(m.size())));
	CHECK_EQ(s.get(-16 + 1, 2, 32 + 3), 5);
	CHECK_EQ(s.get(-16, 0, 32), 6);
	CHECK_EQ(s.voxelCount(), 2u);
	CHECK_EQ(s.changes().size(), 2u);
	s.changes().clear();

	// Replace removes what is not re-sent.
	auto m2 = sectionMsg(-1, 0, 2, ocp::kSecReplace, {{local, 5}});
	CHECK(s.applySectionData(m2.data(), static_cast<uint32_t>(m2.size())));
	CHECK_EQ(s.get(-16, 0, 32), 0);
	bool sawRemoval = false;
	for (const auto& c : s.changes()) sawRemoval |= (c.x == -16 && c.y == 0 && c.z == 32 && c.newId == 0 && c.oldId == 6);
	CHECK(sawRemoval);
	s.changes().clear();

	// Updates: set, change, clear, and the FROM_GTA flag marks the change urgent.
	std::vector<uint8_t> up(sizeof(ocp::VoxelUpdatesHdr) + 2 * sizeof(ocp::VoxelUpdate));
	ocp::VoxelUpdatesHdr uh{2, 0, 7};
	std::memcpy(up.data(), &uh, sizeof uh);
	ocp::VoxelUpdate u0{3, 4, 5, 9, ocp::kUpdFromGta}, u1{-16 + 1, 2, 32 + 3, 0, 0};
	std::memcpy(up.data() + sizeof uh, &u0, sizeof u0);
	std::memcpy(up.data() + sizeof uh + sizeof u0, &u1, sizeof u1);
	CHECK(s.applyVoxelUpdates(up.data(), static_cast<uint32_t>(up.size())));
	CHECK_EQ(s.get(3, 4, 5), 9);
	CHECK_EQ(s.get(-15, 2, 35), 0);
	CHECK(s.changes().size() == 2 && s.changes()[0].urgent && !s.changes()[1].urgent);

	// Unload removes everything in the section and says so.
	s.changes().clear();
	s.unloadSection(0, 0, 0);
	CHECK_EQ(s.get(3, 4, 5), 0);
	CHECK_EQ(s.changes().size(), 1u);
	CHECK_EQ(s.sectionCount(), 1u);
}

TEST(voxel_store_rejects_malformed_records_without_crashing) {
	VoxelStore s;
	std::vector<uint8_t> tiny(4, 0);
	CHECK(!s.applySectionData(tiny.data(), 4));
	auto lie = sectionMsg(0, 0, 0, 0, {{0, 1}});
	lie.resize(lie.size() - 2);  // header claims one entry, payload is short
	CHECK(!s.applySectionData(lie.data(), static_cast<uint32_t>(lie.size())));
	auto bad = sectionMsg(0, 0, 0, 0, {{5000, 1}, {3, 0}, {7, 2}});  // out-of-range local and id 0 are skipped
	CHECK(s.applySectionData(bad.data(), static_cast<uint32_t>(bad.size())));
	CHECK_EQ(s.voxelCount(), 1u);
	CHECK(!s.applyVoxelUpdates(tiny.data(), 4));
}

TEST(palette_resolves_overrides_buckets_and_placeables) {
	Palette p;
	auto add = [&](uint16_t id, uint16_t flags, uint32_t rgb, const char* name) {
		std::vector<uint8_t> b(sizeof(ocp::PaletteEntryHdr) + std::strlen(name));
		ocp::PaletteEntryHdr h{id, flags, rgb, static_cast<uint16_t>(std::strlen(name)), 0};
		std::memcpy(b.data(), &h, sizeof h);
		std::memcpy(b.data() + sizeof h, name, std::strlen(name));
		return p.apply(b.data(), static_cast<uint32_t>(b.size()));
	};
	StyleConfig sc;
	sc.defaultModel = 0xC0FFEE;
	sc.colourBuckets = 8;
	sc.byName["minecraft:glass"] = {0xBEEF, 3};
	p.setStyleConfig(sc);
	CHECK(add(7, ocp::kPalPlaceable | ocp::kPalOpaque, 0x808080, "minecraft:stone"));
	CHECK(add(3, ocp::kPalPlaceable, 0xFFFFFF, "minecraft:glass"));
	CHECK(add(9, 0, 0x123456, "minecraft:fire"));
	CHECK(!p.apply(reinterpret_cast<const uint8_t*>("xx"), 2));
	CHECK_EQ(p.styleFor(3).model, 0xBEEFu);
	CHECK_EQ(p.styleFor(3).variation, 3);
	CHECK_EQ(p.styleFor(7).model, 0xC0FFEEu);
	CHECK(p.styleFor(7).variation >= 0 && p.styleFor(7).variation < 8);
	CHECK(p.placeable() == (std::vector<uint16_t>{3, 7}));
	// Re-resolving after a config change picks up the new default for already-known entries.
	sc.defaultModel = 0x1234;
	p.setStyleConfig(sc);
	CHECK_EQ(p.styleFor(7).model, 0x1234u);
}

// ---------------------------------------------------------------------------------------------------------------
// Prop manager
// ---------------------------------------------------------------------------------------------------------------
namespace {
struct Rig {
	tst::MockGame game;
	VoxelStore store;
	Palette pal;
	Transform tf;
	PropManager props;
	Vec3 player{1000, 2000, 31};

	explicit Rig(PropConfig cfg) : props(game, store, pal, cfg) {
		StyleConfig sc;
		sc.defaultModel = 0xC0FFEE;
		pal.setStyleConfig(sc);
		std::vector<uint8_t> b(sizeof(ocp::PaletteEntryHdr) + 5);
		ocp::PaletteEntryHdr h{1, ocp::kPalPlaceable | ocp::kPalOpaque, 0x808080, 5, 0};
		std::memcpy(b.data(), &h, sizeof h);
		std::memcpy(b.data() + sizeof h, "stone", 5);
		pal.apply(b.data(), static_cast<uint32_t>(b.size()));
		tf.valid = true;
		tf.anchorMc = {0, 0, 0};
		tf.anchorGta = {1000, 2000, 30};
	}
	void plate(int half) {
		for (int x = -half; x <= half; ++x)
			for (int z = -half; z <= half; ++z) store.set(x, 0, z, 1, false);
	}
	void tick(int n = 1) {
		for (int i = 0; i < n; ++i) {
			game.clockUs += 16'667;
			props.tick(tf, player);
		}
	}
	Vec3 playerMc() const { return tf.toMc(player); }
};
}  // namespace

TEST(props_respect_hard_cap_and_spawn_nearest_first) {
	PropConfig cfg;
	cfg.maxProps = 500;
	cfg.spawnRadius = 22;
	Rig r(cfg);
	r.plate(40);  // 6561 voxels, far more than the cap
	r.tick(60);
	CHECK_EQ(r.props.stats().live, 500u);
	CHECK(r.game.props.size() <= 500);
	CHECK_EQ(r.game.activeProps(), 500u);
	const Vec3 pm = r.playerMc();
	double maxLive = 0, minDead = 1e9;
	for (int x = -40; x <= 40; ++x) {
		for (int z = -40; z <= 40; ++z) {
			const double d = std::hypot(x + 0.5 - pm.x, 0.5 - pm.y, z + 0.5 - pm.z);
			if (d > cfg.spawnRadius) continue;
			if (r.props.isLive(x, 0, z)) maxLive = std::max(maxLive, d);
			else minDead = std::min(minDead, d);
		}
	}
	CHECK(maxLive <= minDead + 1.01);  // the live set is (within one block) the nearest 500 voxels
}

TEST(props_follow_player_recycle_and_never_duplicate) {
	PropConfig cfg;
	cfg.maxProps = 700;
	Rig r(cfg);
	r.plate(70);
	r.tick(40);
	const uint64_t createdBefore = r.props.stats().created;
	for (int step = 0; step < 90; ++step) {  // walk 45 blocks east, half a block per frame
		r.player.x += 0.5;
		r.tick();
	}
	r.tick(30);
	const PropStats& st = r.props.stats();
	CHECK(st.recycled > 200);                       // retired props are reused rather than destroyed and recreated
	CHECK(st.created - createdBefore < 150);        // creation stays near the pool size, not the distance walked
	CHECK(r.game.props.size() <= 700);
	CHECK_EQ(st.live, r.game.activeProps());
	// Every live voxel is near the player; nothing is leaked far behind.
	const Vec3 pm = r.playerMc();
	int far = 0;
	std::unordered_set<opt::Handle> seen;
	for (int x = -70; x <= 70; ++x)
		for (int z = -70; z <= 70; ++z) {
			if (!r.props.isLive(x, 0, z)) continue;
			const double d = std::hypot(x + 0.5 - pm.x, 0.5 - pm.y, z + 0.5 - pm.z);
			if (d > cfg.spawnRadius + cfg.retireMargin + 1.0) ++far;
			CHECK(seen.insert(r.props.handleAt(x, 0, z)).second);
		}
	CHECK_EQ(far, 0);
	CHECK(r.game.props.size() > 0);
}

TEST(props_break_removes_same_frame_and_place_is_urgent) {
	PropConfig cfg;
	cfg.maxSpawnsPerFrame = 8;
	Rig r(cfg);
	r.plate(10);
	r.tick(100);
	CHECK(r.props.isLive(2, 0, 2));
	const Handle h = r.props.handleAt(2, 0, 2);
	r.store.set(2, 0, 2, 0, true);
	r.tick();
	CHECK(!r.props.isLive(2, 0, 2));
	CHECK(!r.game.props.at(h).active);  // parked, hidden and non-collidable in the very same tick

	// A freshly placed block jumps the queue even when the budget is tiny and a backlog exists.
	r.plate(40);  // creates a backlog of non-urgent work
	r.store.set(0, 1, 0, 1, true);
	r.tick();
	CHECK(r.props.isLive(0, 1, 0));
	int dx, dy, dz;
	uint8_t face = 0;
	Transform::faceNormal(face, dx, dy, dz);
	int32_t vx, vy, vz;
	CHECK(r.props.voxelOfEntity(r.props.handleAt(0, 1, 0), vx, vy, vz) && vx == 0 && vy == 1 && vz == 0);
}

TEST(props_replacing_a_block_type_rebuilds_the_prop) {
	PropConfig cfg;
	Rig r(cfg);
	r.plate(3);
	r.tick(20);
	const Handle before = r.props.handleAt(0, 0, 0);
	r.store.set(0, 0, 0, 1, true);  // same id: no churn
	r.tick();
	CHECK_EQ(r.props.handleAt(0, 0, 0), before);
	std::vector<uint8_t> b(sizeof(ocp::PaletteEntryHdr) + 4);
	ocp::PaletteEntryHdr h{2, 0, 0, 4, 0};
	std::memcpy(b.data(), &h, sizeof h);
	std::memcpy(b.data() + sizeof h, "dirt", 4);
	r.pal.apply(b.data(), static_cast<uint32_t>(b.size()));
	r.store.set(0, 0, 0, 2, true);
	r.tick();
	CHECK(r.props.isLive(0, 0, 0));
	CHECK_EQ(r.props.stats().live, r.game.activeProps());
}

TEST(props_respect_per_frame_and_time_budgets) {
	PropConfig cfg;
	cfg.maxSpawnsPerFrame = 10;
	Rig r(cfg);
	r.plate(20);
	r.tick();
	CHECK(r.props.stats().spawnedLastFrame <= 10);
	CHECK(r.props.stats().spawnedLastFrame > 0);

	PropConfig cfg2;
	cfg2.maxSpawnsPerFrame = 1000;
	cfg2.frameBudgetUs = 1000;
	Rig r2(cfg2);
	r2.game.nativeCostUs = 100;  // every native costs 100 us: a create is ~400 us, so ~3 fit in 1 ms
	r2.plate(20);
	r2.tick();
	CHECK(r2.props.stats().spawnedLastFrame >= 1 && r2.props.stats().spawnedLastFrame <= 8);
}

TEST(props_wait_for_model_streaming_then_spawn) {
	PropConfig cfg;
	Rig r(cfg);
	r.game.loadDelayFrames[0xC0FFEE] = 6;
	r.plate(5);
	r.tick(3);
	CHECK_EQ(r.game.props.size(), 0u);
	r.tick(40);
	CHECK(r.game.props.size() > 100);
	CHECK_EQ(r.game.modelRequests, 1);  // requested exactly once
}

TEST(props_skip_missing_models_without_spinning) {
	PropConfig cfg;
	Rig r(cfg);
	r.game.validModels.clear();  // the configured model does not exist in this game build
	r.plate(10);
	r.tick(5);
	CHECK_EQ(r.game.props.size(), 0u);
	CHECK(r.props.stats().modelsMissing >= 1);
}

TEST(props_self_heal_when_the_engine_deletes_entities) {
	PropConfig cfg;
	cfg.maxProps = 300;
	Rig r(cfg);
	r.plate(12);
	r.tick(40);
	const uint32_t live = r.props.stats().live;
	CHECK(live > 100);
	int killed = 0;
	for (auto it = r.game.props.begin(); it != r.game.props.end() && killed < 25;) {
		it = r.game.props.erase(it);
		++killed;
	}
	r.tick(80);
	CHECK_EQ(r.props.stats().live, live);
	CHECK(r.props.stats().respawned >= 10);
	r.props.shutdown();
	CHECK_EQ(r.game.props.size(), 0u);
}

TEST(props_adapt_when_engine_runs_out_of_object_slots) {
	PropConfig cfg;
	cfg.maxProps = 1000;
	Rig r(cfg);
	r.game.entityLimit = 300;
	r.plate(30);
	r.tick(300);
	CHECK(r.game.props.size() <= 300);
	CHECK(r.props.stats().live >= 250);
	CHECK(r.props.stats().createFailures > 0);
	CHECK(r.props.stats().createFailures < 100);  // backs off instead of hammering the engine every frame
}

TEST(props_apply_model_origin_offset_so_cube_centres_on_voxel) {
	PropConfig cfg;
	Rig r(cfg);
	r.game.dims[0xC0FFEE] = {true, {-0.5, -0.5, 0.0}, {0.5, 0.5, 1.0}};  // origin at the base, like most props
	r.store.set(0, 0, 0, 1, true);
	r.tick(3);
	CHECK_EQ(r.game.props.size(), 1u);
	const Vec3 p = r.game.props.begin()->second.pos;
	const Vec3 centre = r.tf.voxelCenterGta(0, 0, 0);
	CHECK_NEAR(p.x, centre.x, 1e-9);
	CHECK_NEAR(p.y, centre.y, 1e-9);
	CHECK_NEAR(p.z, centre.z - 0.5, 1e-9);  // origin sits half a metre below the voxel centre
}

TEST(props_reanchor_parks_everything_and_rebuilds_at_new_position) {
	PropConfig cfg;
	Rig r(cfg);
	r.plate(6);
	r.tick(20);
	const size_t n = r.game.props.size();
	r.tf.anchorGta = {5000, 6000, 40};
	r.player = {5000, 6000, 41};
	r.props.onTransformChanged();
	r.tick(40);
	CHECK_EQ(r.props.stats().live, n);
	for (const auto& kv : r.game.props) {
		if (!kv.second.active) continue;
		CHECK(kv.second.pos.x > 4900 && kv.second.pos.x < 5100);
	}
}

int main(int argc, char** argv) { return tst::runAll(argc > 1 ? argv[1] : nullptr); }
