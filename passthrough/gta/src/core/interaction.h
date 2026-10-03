// Feeds GTA V's physics and weapon results back to Minecraft.
//
//   * Weapon fire: when the player shoots, GTA reports the bullet impact point. We cast a GTA line-of-sight probe
//     (the same physics world the bullet used) toward it, accept the hit only if the first thing it strikes is one of
//     our voxel props, and send BREAK_REQUEST with the voxel, face and weapon damage.
//   * Crosshair tools: hotkeys mine or place at whatever voxel the camera ray strikes.
//   * Collisions: when the player (on foot or driving) loses a lot of speed in one frame while touching the voxel
//     shell, send a COLLISION_EVENT with the contact voxel, normal and impact speed so Minecraft can crack the block.
#pragma once

#include <cstdint>
#include <unordered_set>

#include "bridge.h"
#include "game_api.h"
#include "prop_manager.h"
#include "transform.h"
#include "voxel_store.h"

namespace opt {

struct InteractionConfig {
	float reach = 7.0f;                  // metres from the player for crosshair tools
	float toolDamage = 60.0f;
	float explosiveDamage = 400.0f;
	float bulletRange = 300.0f;
	float impactMatchTolerance = 1.5f;   // probe hit must land this close to GTA's reported bullet impact
	uint32_t shotMinIntervalMs = 40;     // rate limit (a minigun fires far faster than we need to report)
	float collisionMinDeltaV = 4.0f;     // m/s lost in one frame by a vehicle before it counts as an impact
	float pedCollisionMinDeltaV = 9.0f;
	uint32_t collisionCooldownMs = 250;
	float vehicleMassKg = 1500.0f;
	float pedMassKg = 80.0f;
	bool shots = true;
	bool collisions = true;
};

struct InteractionCounters {
	uint64_t shotsSeen = 0, shotsMatched = 0, breaksSent = 0, placesSent = 0, collisionsSent = 0, probeMisses = 0;
};

class Interaction {
public:
	Interaction(GameApi& game, Bridge& bridge, PropManager& props, const VoxelStore& store, const Transform& tf,
	            InteractionConfig cfg = {});

	void setConfig(const InteractionConfig& c) { cfg_ = c; }
	// Per frame: detects shots and impacts. Requires an anchored transform and a live Minecraft.
	void frame(const PlayerSample& p, uint64_t nowUs);
	// Hotkey actions against the crosshair. Return true if a request was queued.
	bool mine(const PlayerSample& p);
	bool place(const PlayerSample& p, uint16_t paletteId);

	const InteractionCounters& counters() const { return counters_; }

private:
	struct Target {
		bool ok = false;
		int32_t x = 0, y = 0, z = 0;
		uint8_t face = 0;
		Vec3 point;
	};
	Target trace(Vec3 from, Vec3 to, Handle ignore);
	Target crosshair(const PlayerSample& p);
	void handleShot(const PlayerSample& p, uint64_t nowUs);
	void handleCollision(const PlayerSample& p, uint64_t nowUs);

	GameApi& game_;
	Bridge& bridge_;
	PropManager& props_;
	const VoxelStore& store_;
	const Transform& tf_;
	InteractionConfig cfg_;
	InteractionCounters counters_;

	std::unordered_set<uint32_t> explosive_;
	bool weaponsInit_ = false;
	uint64_t lastShotUs_ = 0;
	uint64_t collisionCooldownUntilUs_ = 0;
	bool havePrev_ = false;
	Vec3 prevVel_;
	double prevSpeed_ = 0;
	Handle prevEntity_ = 0;
};

}  // namespace opt
