// Deterministic in-memory GTA V used by the unit tests. It keeps real props with real bounding boxes and answers
// ray casts against them, so interaction code is tested against actual geometry rather than canned results.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "../src/core/game_api.h"

namespace tst {

class MockGame : public opt::GameApi {
public:
	struct Prop {
		uint32_t model = 0;
		opt::Vec3 pos;
		bool active = true;
		int variation = -1;
	};

	// ---- clock: every call into the "engine" costs time, so budgets can be tested ----
	uint64_t clockUs = 1'000'000;
	uint64_t nativeCostUs = 0;
	uint64_t nowMicros() override { return clockUs; }
	void charge(uint64_t n = 1) { clockUs += nativeCostUs * n; }

	uint32_t joaat(const char* s) override {
		uint32_t h = 0;
		for (; *s; ++s) {
			h += static_cast<uint8_t>(std::tolower(static_cast<unsigned char>(*s)));
			h += h << 10;
			h ^= h >> 6;
		}
		h += h << 3;
		h ^= h >> 11;
		h += h << 15;
		return h;
	}

	// ---- models ----
	std::unordered_set<uint32_t> validModels{0xC0FFEE, 0xBEEF};
	std::unordered_map<uint32_t, int> loadDelayFrames;  // model -> frames until loaded
	std::unordered_set<uint32_t> requested;
	std::unordered_map<uint32_t, opt::ModelDims> dims;
	int modelRequests = 0;

	bool modelExists(uint32_t m) override { return validModels.count(m) != 0; }
	void requestModel(uint32_t m) override { requested.insert(m); ++modelRequests; }
	bool modelLoaded(uint32_t m) override {
		charge();
		if (!requested.count(m)) return false;
		auto it = loadDelayFrames.find(m);
		if (it != loadDelayFrames.end() && it->second > 0) {
			--it->second;
			return false;
		}
		return true;
	}
	void releaseModel(uint32_t m) override { requested.erase(m); }
	opt::ModelDims modelDimensions(uint32_t m) override {
		auto it = dims.find(m);
		if (it != dims.end()) return it->second;
		return {true, {-0.5, -0.5, -0.5}, {0.5, 0.5, 0.5}};
	}

	// ---- props ----
	std::unordered_map<opt::Handle, Prop> props;
	opt::Handle nextHandle = 1000;
	size_t entityLimit = std::numeric_limits<size_t>::max();
	int createCalls = 0, destroyCalls = 0, moveCalls = 0, activeCalls = 0;

	opt::Handle createProp(uint32_t model, opt::Vec3 pos) override {
		charge(4);
		++createCalls;
		if (props.size() >= entityLimit) return 0;
		const opt::Handle h = nextHandle++;
		props[h] = Prop{model, pos, true, -1};
		return h;
	}
	void destroyProp(opt::Handle h) override { charge(2); ++destroyCalls; props.erase(h); }
	bool propExists(opt::Handle h) override { charge(); return props.count(h) != 0; }
	void moveProp(opt::Handle h, opt::Vec3 pos) override { charge(); ++moveCalls; props.at(h).pos = pos; }
	void setPropActive(opt::Handle h, bool a) override { charge(); ++activeCalls; props.at(h).active = a; }
	void setPropVariation(opt::Handle h, int v) override { props.at(h).variation = v; }

	size_t activeProps() const {
		size_t n = 0;
		for (const auto& kv : props) n += kv.second.active ? 1 : 0;
		return n;
	}

	// ---- player ----
	opt::PlayerSample player;
	float damagePerWeapon = 26.0f;
	opt::PlayerSample samplePlayer() override { return player; }
	float weaponDamage(uint32_t) override { return damagePerWeapon; }

	// Slab-method ray vs. every active prop's world AABB.
	opt::RayHit castRay(opt::Vec3 from, opt::Vec3 to, opt::Handle ignore) override {
		charge(2);
		opt::RayHit best;
		const opt::Vec3 d = to - from;
		double bestT = 2.0;
		for (const auto& kv : props) {
			if (!kv.second.active || kv.first == ignore) continue;
			const opt::ModelDims md = modelDimensions(kv.second.model);
			const double lo[3] = {kv.second.pos.x + md.min.x, kv.second.pos.y + md.min.y, kv.second.pos.z + md.min.z};
			const double hi[3] = {kv.second.pos.x + md.max.x, kv.second.pos.y + md.max.y, kv.second.pos.z + md.max.z};
			const double o[3] = {from.x, from.y, from.z};
			const double dd[3] = {d.x, d.y, d.z};
			double t0 = 0, t1 = 1;
			int hitAxis = -1, hitSign = 0;
			bool ok = true;
			for (int a = 0; a < 3 && ok; ++a) {
				if (std::fabs(dd[a]) < 1e-12) {
					if (o[a] < lo[a] || o[a] > hi[a]) ok = false;
					continue;
				}
				double ta = (lo[a] - o[a]) / dd[a], tb = (hi[a] - o[a]) / dd[a];
				int sign = -1;  // entering through the low face: the normal points toward -axis
				if (ta > tb) {
					std::swap(ta, tb);
					sign = 1;
				}
				if (ta > t0) {
					t0 = ta;
					hitAxis = a;
					hitSign = sign;
				}
				t1 = std::min(t1, tb);
				if (t0 > t1) ok = false;
			}
			if (!ok || hitAxis < 0 || t0 >= bestT) continue;
			bestT = t0;
			best.hit = true;
			best.entity = kv.first;
			best.point = {from.x + d.x * t0, from.y + d.y * t0, from.z + d.z * t0};
			best.normal = {0, 0, 0};
			(hitAxis == 0 ? best.normal.x : hitAxis == 1 ? best.normal.y : best.normal.z) = hitSign;
		}
		return best;
	}

	// ---- UI ----
	std::vector<std::string> notifications;
	bool online = false;
	void notify(const char* t) override { notifications.emplace_back(t); }
	void drawText(float, float, float, const char*) override {}
	bool onlineSession() override { return online; }
};

}  // namespace tst
