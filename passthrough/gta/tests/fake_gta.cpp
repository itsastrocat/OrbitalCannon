// End-to-end GTA client for the cross-language test: the REAL GTA-side App (bridge, world sync, voxel store, prop
// manager, interaction, config) running in real time against a mock GTA, talking over real shared memory to the REAL
// Minecraft/Fabric mod running in a headless dedicated server. Every phase asserts on state that only exists if the
// Java side did the right thing, and the run reports measured latencies.
//
//   fake_gta <shm-name-or-path> [--seconds N]
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <memory>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "mock_game.h"
#include "../src/core/app.h"
#include "../src/core/log.h"
#include "../src/core/raycast.h"

using namespace opt;
using Clock = std::chrono::steady_clock;

namespace {

class RealtimeGame final : public tst::MockGame {
public:
	uint64_t nowMicros() override {
		return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(Clock::now().time_since_epoch()).count());
	}
};

int g_failures = 0;
void report(const char* name, bool ok, const std::string& detail) {
	std::printf("E2E %-34s %s  %s\n", name, ok ? "PASS" : "FAIL", detail.c_str());
	std::fflush(stdout);
	if (!ok) ++g_failures;
}
std::string fmt(const char* f, ...) __attribute__((format(printf, 1, 2)));
std::string fmt(const char* f, ...) {
	char buf[512];
	va_list ap;
	va_start(ap, f);
	std::vsnprintf(buf, sizeof buf, f, ap);
	va_end(ap);
	return buf;
}

struct Rig {
	RealtimeGame game;
	std::unique_ptr<App> app;
	std::string shm;

	void start() {
		Config c;
		c.shmName = shm;
		c.model = "prop_e2e_cube";
		game.validModels.insert(game.joaat("prop_e2e_cube"));
		c.props.maxProps = 3000;
		c.props.spawnRadius = 22;
		c.props.maxSpawnsPerFrame = 96;
		c.viewRadiusH = 28;
		c.viewRadiusV = 24;
		c.overlay = false;
		c.inter.shotMinIntervalMs = 40;
		app = std::make_unique<App>(game, c);
		game.player.valid = true;
		game.player.ped = 7;
		game.player.feet = {1000, 2000, 30};
		game.player.flags = ocp::kPfOnFoot;
		game.player.camPos = {1000, 1996, 34};
		game.player.camDir = normalized(Vec3{0, 1, -1});
		game.player.hitMin[0] = game.player.hitMin[1] = -0.35f;
		game.player.hitMax[0] = game.player.hitMax[1] = 0.35f;
		game.player.hitMax[2] = 1.8f;
	}

	void frame() {
		app->frame();
		std::this_thread::sleep_for(std::chrono::milliseconds(8));  // ~120 fps: sub-frame polling granularity
	}

	// Runs frames until pred() holds or the timeout elapses; returns elapsed milliseconds, or -1 on timeout.
	double waitUntil(const std::function<bool()>& pred, double timeoutMs) {
		const auto t0 = Clock::now();
		for (;;) {
			frame();
			if (pred()) return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
			if (std::chrono::duration<double, std::milli>(Clock::now() - t0).count() > timeoutMs) return -1;
		}
	}

	void run(double ms) {
		const auto t0 = Clock::now();
		while (std::chrono::duration<double, std::milli>(Clock::now() - t0).count() < ms) frame();
	}

	// Points the camera so the crosshair lands on the centre of the top face of a voxel.
	void aimAtTop(int x, int y, int z) {
		const Vec3 top = app->transform().voxelCenterGta(x, y, z) + Vec3{0, 0, 0.5};
		game.player.camPos = top + Vec3{0, -3, 3};
		game.player.camDir = normalized(Vec3{0, 1, -1});
		game.player.shooting = false;
		game.player.hasImpact = false;
	}
	Vec3 topOf(int x, int y, int z) { return app->transform().voxelCenterGta(x, y, z) + Vec3{0, 0, 0.5}; }
	std::string nameAt(int x, int y, int z) {
		const PaletteInfo* i = app->palette().find(app->store().get(x, y, z));
		return i ? i->name : "?";
	}
};

bool breakableName(const std::string& n) {
	return n == "minecraft:dirt" || n == "minecraft:grass_block" || n == "minecraft:stone" || n == "minecraft:sand" ||
	       n == "minecraft:gravel" || n == "minecraft:coarse_dirt" || n == "minecraft:podzol" || n == "minecraft:snow_block";
}

}  // namespace

int main(int argc, char** argv) {
	if (argc < 2) {
		std::fprintf(stderr, "usage: fake_gta <shm-name-or-path> [--seconds N]\n");
		return 2;
	}
	Rig r;
	r.shm = argv[1];
	opt::setLogSink([](const char* l) { std::fprintf(stderr, "  [gta] %s\n", l); });
	r.start();
	App& app = *r.app;

	// ---- 1. connect ----------------------------------------------------------------------------------------------
	double t = r.waitUntil([&] { return app.sync().session().active; }, 45000);
	report("connect+session", t >= 0, t >= 0 ? fmt("session after %.0f ms, MC anchor (%.1f, %.1f, %.1f)", t, app.sync().session().anchorMc.x, app.sync().session().anchorMc.y, app.sync().session().anchorMc.z) : "timed out waiting for Minecraft");
	if (t < 0) return 1;
	const Vec3 mcAnchor = app.sync().session().anchorMc;

	// ---- 1b. a dedicated server with no real players must keep simulating while GTA is attached ------------------------
	// The server runs with pause-when-empty-seconds=3. Without the passthrough's mixin it would stop ticking after 3 s.
	{
		ocp::McState a{}, b{};
		r.waitUntil([&] { return app.bridge().readMcState(a); }, 5000);
		r.run(6000);
		app.bridge().readMcState(b);
		const unsigned long long ticks = static_cast<unsigned long long>(b.tick - a.tick);
		report("server-keeps-ticking", ticks >= 100, fmt("%llu server ticks in 6 s with zero real players and pause-when-empty-seconds=3 (20 TPS => ~120)", ticks));
	}

	// ---- 2. anchor and stream --------------------------------------------------------------------------------------
	r.frame();
	const auto anchoredAt = Clock::now();
	app.onKey(0x78);  // F9
	r.frame();
	report("anchor", app.transform().valid, fmt("GTA (%.1f, %.1f, %.1f)", app.transform().anchorGta.x, app.transform().anchorGta.y, app.transform().anchorGta.z));
	double firstVoxel = r.waitUntil([&] { return app.store().voxelCount() > 0; }, 15000);
	report("first-voxel-latency", firstVoxel >= 0, fmt("%.0f ms from ANCHOR to the first streamed voxel", firstVoxel));
	double settled = r.waitUntil([&] { return app.props().stats().live > 250 && app.props().stats().queued == 0; }, 30000);
	const double streamMs = std::chrono::duration<double, std::milli>(Clock::now() - anchoredAt).count();
	report("stream-and-render", settled >= 0,
	       fmt("%zu sections, %zu visible voxels, %u props live, %.0f ms after anchor; mc %.1f mspt, rtt %.2f ms", app.store().sectionCount(),
	           app.store().voxelCount(), app.props().stats().live, streamMs, app.bridge().stats().mcMspt, app.bridge().stats().rttMs));
	if (settled < 0) return 1;
	// Let Minecraft finish streaming the whole view before choosing targets (it reports progress in its McState slot).
	r.waitUntil([&] {
		ocp::McState ms{};
		return app.bridge().readMcState(ms) && ms.sections_pending == 0 && ms.sections_streamed >= 9 && app.props().stats().queued == 0;
	}, 30000);
	{
		ocp::McState ms{};
		app.bridge().readMcState(ms);
		report("streaming-complete", ms.sections_pending == 0, fmt("Minecraft reports %u sections streamed, %u pending; GTA holds %zu sections / %zu voxels / %u props", ms.sections_streamed, ms.sections_pending, app.store().sectionCount(), app.store().voxelCount(), app.props().stats().live));
	}
	report("palette-before-use", app.sync().counters().palette > 0 && app.sync().counters().malformed == 0,
	       fmt("%llu palette entries, %llu malformed records", static_cast<unsigned long long>(app.sync().counters().palette), static_cast<unsigned long long>(app.sync().counters().malformed)));

	// ---- choose well-separated targets with an unobstructed line of sight ---------------------------------------------
	// The camera looks down at 45 degrees from the south, so a target is only hittable if nothing taller stands in the way.
	const int ax = static_cast<int>(std::floor(mcAnchor.x)), ay = static_cast<int>(std::floor(mcAnchor.y)), az = static_cast<int>(std::floor(mcAnchor.z));
	struct Col { int x, y, z; };
	const auto rayClear = [&](const Col& c, const Col& ignore) {
		for (double step = 0.2; step <= 3.0; step += 0.2) {
			const int vx = c.x, vy = static_cast<int>(std::floor(c.y + 1 + step)), vz = static_cast<int>(std::floor(c.z + 0.5 + step));
			if (vx == ignore.x && vy == ignore.y && vz == ignore.z) continue;
			if (r.app->store().get(vx, vy, vz) != 0) return false;
		}
		return true;
	};
	std::vector<Col> pool;
	for (int dx = -19; dx <= 19; ++dx) {
		for (int dz = -19; dz <= 19; ++dz) {
			if (std::hypot(dx, dz) > 19.0) continue;  // inside the 22 m prop radius, so every target has a prop
			for (int y = ay + 4; y >= ay - 10; --y) {
				if (r.app->store().get(ax + dx, y, az + dz) != 0) {
					const Col c{ax + dx, y, az + dz};
					// The block below is buried (not streamed) until the surface one is mined, so only its line of sight is checked.
					if (r.app->store().get(c.x, y + 1, c.z) == 0 && breakableName(r.nameAt(c.x, c.y, c.z)) && rayClear(c, c) &&
					    rayClear({c.x, c.y - 1, c.z}, c))
						pool.push_back(c);
					break;
				}
			}
		}
	}
	std::sort(pool.begin(), pool.end(), [&](const Col& a, const Col& b) {
		return std::hypot(a.x - ax, a.z - az) < std::hypot(b.x - ax, b.z - az);
	});
	std::vector<Col> candidates;  // greedy: nearest first, at least 7 blocks from every earlier pick
	for (const Col& c : pool) {
		bool apart = true;
		for (const Col& o : candidates) apart = apart && std::hypot(c.x - o.x, c.z - o.z) >= 7.0;
		if (apart) candidates.push_back(c);
		if (candidates.size() == 6) break;
	}
	report("targets-near-anchor", candidates.size() >= 5,
	       fmt("%zu clear, breakable, well separated surface targets (pool of %zu); first is %s at (%d,%d,%d); anchor y=%.0f", candidates.size(), pool.size(),
	           candidates.empty() ? "-" : r.nameAt(candidates[0].x, candidates[0].y, candidates[0].z).c_str(), candidates.empty() ? 0 : candidates[0].x,
	           candidates.empty() ? 0 : candidates[0].y, candidates.empty() ? 0 : candidates[0].z, mcAnchor.y));
	// Only the mining target is limited by Minecraft's 12 m tool reach (bullets reach 350 m, rockets too, and the car drives
	// to its target), so it is the nearest pick, and it must be inside that reach.
	if (candidates.size() < 5 || std::hypot(candidates[0].x - mcAnchor.x, candidates[0].z - mcAnchor.z) > 10.0) {
		report("targets-in-reach", false, "the nearest target is outside tool reach or too few targets");
		return 1;
	}

	// ---- 3. mine with the crosshair tool; measure round trip ----------------------------------------------------
	const Col m = candidates[0];
	const std::string mName = r.nameAt(m.x, m.y, m.z);
	r.waitUntil([&] { return app.props().isLive(m.x, m.y, m.z); }, 5000);
	r.aimAtTop(m.x, m.y, m.z);
	r.frame();
	const auto mineStart = Clock::now();
	app.onKey(0x76);  // F7
	double mined = r.waitUntil([&] { return app.store().get(m.x, m.y, m.z) == 0; }, 5000);
	const double mineMs = mined >= 0 ? std::chrono::duration<double, std::milli>(Clock::now() - mineStart).count() : -1;
	report("mine-roundtrip", mined >= 0, fmt("%s at (%d,%d,%d) removed from GTA's mirror %.1f ms after the key press", mName.c_str(), m.x, m.y, m.z, mineMs));
	report("mine-prop-removed", !app.props().isLive(m.x, m.y, m.z), "the prop for the broken block is gone the same frame");
	r.run(60);
	report("mine-ack-broken", app.lastAckText().find("broken") != std::string::npos, "ACK: " + app.lastAckText());
	// The block below was buried; breaking exposes it and Minecraft must stream it.
	double revealed = r.waitUntil([&] { return app.store().get(m.x, m.y - 1, m.z) != 0; }, 3000);
	report("reveal-newly-exposed", revealed >= 0, fmt("the block under the hole (%s) appeared %.0f ms later", r.nameAt(m.x, m.y - 1, m.z).c_str(), revealed));

	// ---- 4. place a block back ----------------------------------------------------------------------------------
	app.onKey(0xDD);  // ] choose the first placeable block
	r.waitUntil([&] { return app.props().isLive(m.x, m.y - 1, m.z); }, 5000);
	r.aimAtTop(m.x, m.y - 1, m.z);  // crosshair on the (now exposed) block's top face: new block goes on top of it
	r.frame();
	const auto placeStart = Clock::now();
	app.onKey(0x75);  // F6
	double placed = r.waitUntil([&] { return app.store().get(m.x, m.y, m.z) != 0; }, 5000);
	const double placeMs = placed >= 0 ? std::chrono::duration<double, std::milli>(Clock::now() - placeStart).count() : -1;
	report("place-roundtrip", placed >= 0, fmt("%s now at (%d,%d,%d), %.1f ms after the key press", r.nameAt(m.x, m.y, m.z).c_str(), m.x, m.y, m.z, placeMs));
	double propBack = r.waitUntil([&] { return app.props().isLive(m.x, m.y, m.z); }, 3000);
	report("place-prop-spawned", propBack >= 0, "the placed block is rendered as a prop");

	// ---- 5. refusals are explained, not silent ----------------------------------------------------------------------
	{
		// A visible block 14-20 m away: GTA happily raycasts it, but Minecraft's tool reach is 12 m and must refuse.
		bool found = false;
		Col far{};
		for (int dx = -20; dx <= 20 && !found; ++dx) {
			for (int dz = -20; dz <= 20 && !found; ++dz) {
				const double d = std::hypot(dx, dz);
				if (d < 14.0 || d > 19.0) continue;
				for (int y = ay + 6; y >= ay - 12; --y) {
					if (r.app->store().get(ax + dx, y, az + dz) != 0) {
						if (r.app->store().get(ax + dx, y + 1, az + dz) == 0 && r.app->props().isLive(ax + dx, y, az + dz)) {
							far = {ax + dx, y, az + dz};
							found = true;
						}
						break;
					}
				}
			}
		}
		if (found) {
			r.aimAtTop(far.x, far.y, far.z);
			r.frame();
			const uint64_t before = app.sync().counters().acks;
			app.onKey(0x76);
			double acked = r.waitUntil([&] { return app.sync().counters().acks > before; }, 3000);
			report("denied-out-of-reach", acked >= 0 && app.lastAckText().find("out of reach") != std::string::npos,
			       fmt("%.0f m away: ACK \"%s\"", std::hypot(far.x - mcAnchor.x, far.z - mcAnchor.z), app.lastAckText().c_str()));
			r.run(100);
			report("denied-block-intact", app.store().get(far.x, far.y, far.z) != 0, "the out-of-range block was not broken");
		} else {
			report("denied-out-of-reach", false, "no visible block 14-19 m from the anchor to test with");
		}
	}

	// ---- 6. bullets: accumulate damage until the block breaks -------------------------------------------------------
	{
		const Col b = candidates[1];
		r.waitUntil([&] { return app.props().isLive(b.x, b.y, b.z); }, 5000);
		r.aimAtTop(b.x, b.y, b.z);
		r.game.player.shooting = true;
		r.game.player.hasImpact = true;
		r.game.player.impact = r.topOf(b.x, b.y, b.z);
		r.game.player.weapon = r.game.joaat("WEAPON_PISTOL");
		r.game.damagePerWeapon = 8.0f;  // weak bullets so that several hits are needed
		const uint64_t sent0 = app.interaction().counters().breaksSent;
		const auto shotStart = Clock::now();
		double broke = r.waitUntil([&] { return app.store().get(b.x, b.y, b.z) == 0; }, 8000);
		const double shotMs = std::chrono::duration<double, std::milli>(Clock::now() - shotStart).count();
		r.game.player.shooting = false;
		r.game.player.hasImpact = false;
		const uint64_t shots = app.interaction().counters().breaksSent - sent0;
		report("bullets-break-after-damage", broke >= 0 && shots >= 2, fmt("%s needed %llu hits of 8 damage (%.0f ms); a single weak bullet must not break a block", r.nameAt(b.x, b.y - 1, b.z).c_str(), static_cast<unsigned long long>(shots), shotMs));
	}

	// ---- 7. rocket: area destruction ------------------------------------------------------------------------------
	{
		const Col c = candidates[2];
		r.waitUntil([&] { return app.props().isLive(c.x, c.y, c.z); }, 5000);
		std::vector<Col> before;
		for (int dx = -3; dx <= 3; ++dx)
			for (int dz = -3; dz <= 3; ++dz)
				for (int dy = -3; dy <= 3; ++dy)
					if (app.store().get(c.x + dx, c.y + dy, c.z + dz) != 0) before.push_back({c.x + dx, c.y + dy, c.z + dz});
		r.aimAtTop(c.x, c.y, c.z);
		r.game.player.shooting = true;
		r.game.player.hasImpact = true;
		r.game.player.impact = r.topOf(c.x, c.y, c.z);
		r.game.player.weapon = r.game.joaat("WEAPON_RPG");
		r.waitUntil([&] { return app.store().get(c.x, c.y, c.z) == 0; }, 5000);
		r.game.player.shooting = false;
		r.game.player.hasImpact = false;
		r.run(500);
		int removed = 0;
		for (const Col& v : before) removed += app.store().get(v.x, v.y, v.z) == 0;
		report("rocket-area-destruction", removed >= 8, fmt("%d of %d visible voxels around the impact were destroyed by one rocket (radius 3.5 sphere)", removed, static_cast<int>(before.size())));
	}

	// ---- 8. vehicle impact feeds collision events to Minecraft -------------------------------------------------------
	{
		const Col v = candidates[3];
		r.waitUntil([&] { return app.props().isLive(v.x, v.y, v.z); }, 5000);
		std::vector<Col> before;
		for (int dx = -4; dx <= 4; ++dx)
			for (int dz = -4; dz <= 4; ++dz)
				for (int dy = -3; dy <= 2; ++dy)
					if (app.store().get(v.x + dx, v.y + dy, v.z + dz) != 0) before.push_back({v.x + dx, v.y + dy, v.z + dz});
		PlayerSample& p = r.game.player;
		p.vehicle = 55;
		p.flags = ocp::kPfInVehicle;
		p.hitMin[0] = p.hitMin[1] = -1.0f;
		p.hitMax[0] = p.hitMax[1] = 1.0f;
		p.hitMin[2] = 0;
		p.hitMax[2] = 1.5f;
		const Vec3 vc = r.app->transform().voxelCenterGta(v.x, v.y, v.z);
		p.feet = vc + Vec3{0, 3.2, 0.5};  // north of the voxel, resting on the ground plane, driving south (+Z in Minecraft)
		p.velocity = {0, -24, 0};
		r.frame();
		r.frame();
		const uint64_t coll0 = app.interaction().counters().collisionsSent;
		p.feet = vc + Vec3{0, 1.0, 0.5};
		p.velocity = {0, -2, 0};
		r.frame();
		const bool sent = app.interaction().counters().collisionsSent > coll0;
		r.run(600);
		int removed = 0;
		for (const Col& b : before) removed += app.store().get(b.x, b.y, b.z) == 0;
		p.vehicle = 0;
		p.flags = ocp::kPfOnFoot;
		p.velocity = {0, 0, 0};
		p.feet = {1000, 2000, 30};
		p.hitMin[0] = p.hitMin[1] = -0.35f;
		p.hitMax[0] = p.hitMax[1] = 0.35f;
		p.hitMax[2] = 1.8f;
		report("vehicle-collision-event", sent, "a 22 m/s speed loss against the voxel shell produced a COLLISION_EVENT");
		report("vehicle-breaks-blocks", removed >= 1, fmt("%d voxel(s) near the contact point were destroyed (363 kJ of impact energy vs a few kJ per block)", removed));
	}

	// ---- 9. GTA restarts: Minecraft must remember every edit ---------------------------------------------------------
	{
		r.app.reset();  // the GTA process "exits"; a brand-new one with a brand-new epoch starts
		r.start();
		App& app2 = *r.app;
		double back = r.waitUntil([&] { return app2.sync().session().active; }, 20000);
		report("reconnect-new-session", back >= 0, fmt("fresh GTA process attached and got a new session after %.0f ms", back));
		app2.onKey(0x78);
		double reloaded = r.waitUntil([&] { return app2.props().stats().live > 250 && app2.props().stats().queued == 0; }, 30000);
		report("reconnect-world-restreamed", reloaded >= 0, fmt("%u props live again", app2.props().stats().live));
		// Minecraft is the source of truth: the block we mined and replaced is a placed block (not the original) and the
		// bullet-broken one is still missing.
		report("state-survives-reconnect", app2.store().get(m.x, m.y, m.z) != 0 && app2.store().get(candidates[1].x, candidates[1].y, candidates[1].z) == 0,
		       fmt("placed block at (%d,%d,%d) = %s; bullet hole at (%d,%d,%d) still empty", m.x, m.y, m.z, r.nameAt(m.x, m.y, m.z).c_str(), candidates[1].x, candidates[1].y, candidates[1].z));
		const BridgeStats& bs = app2.bridge().stats();
		report("ipc-health", bs.corruptIn == 0 && app2.sync().counters().malformed == 0, fmt("corrupt records in=%llu, dropped out=%llu, malformed=%llu, rtt %.2f ms (max %.2f)", static_cast<unsigned long long>(bs.corruptIn), static_cast<unsigned long long>(bs.droppedOut), static_cast<unsigned long long>(app2.sync().counters().malformed), bs.rttMs, bs.rttMaxMs));
	}

	std::printf("\nE2E SUMMARY mine=%.1fms place=%.1fms first_voxel=%.0fms failures=%d\n", mineMs, placeMs, firstVoxel, g_failures);
	r.app->shutdown();
	return g_failures == 0 ? 0 : 1;
}
