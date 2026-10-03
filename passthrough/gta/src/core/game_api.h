// The one seam between the passthrough logic and GTA V. The ScriptHookV implementation (shv/natives.cpp) calls real
// natives; the unit tests supply a deterministic mock. Everything above this interface is plain portable C++.
//
// Calls are virtual on purpose: the hot paths issue at most a few hundred per frame, and each real call is a
// ScriptHookV native dispatch costing far more than a vtable hop.
#pragma once

#include <cstdint>

#include "common.h"

namespace opt {

struct ModelDims {
	bool ok = false;
	Vec3 min, max;  // model-space bounding box
};

struct RayHit {
	bool hit = false;
	Vec3 point;
	Vec3 normal;     // surface normal, GTA axes, unit length
	Handle entity = 0;
};

struct PlayerSample {
	bool valid = false;
	Handle ped = 0;
	Handle vehicle = 0;       // 0 on foot
	Vec3 feet;                // ped feet (or vehicle centre when driving), GTA world
	Vec3 velocity;
	float heading = 0;        // degrees
	Vec3 camPos;              // gameplay camera origin
	Vec3 camDir;              // unit forward vector of the gameplay camera, GTA axes
	float hitMin[3] = {0, 0, 0};  // axis-aligned hitbox offsets relative to `feet`
	float hitMax[3] = {0, 0, 0};
	uint32_t flags = 0;       // ocp::PlayerFlags subset: on foot, in vehicle, shooting, dead, ragdoll
	uint32_t weapon = 0;
	uint32_t vehicleModel = 0;
	float vehicleSpeed = 0;
	bool shooting = false;
	bool hasImpact = false;   // GTA reported where the last bullet landed
	Vec3 impact;
};

class GameApi {
public:
	virtual ~GameApi() = default;

	virtual uint64_t nowMicros() = 0;  // monotonic
	virtual uint32_t joaat(const char* s) = 0;

	// ---- Models ----
	virtual bool modelExists(uint32_t model) = 0;
	virtual void requestModel(uint32_t model) = 0;
	virtual bool modelLoaded(uint32_t model) = 0;
	virtual void releaseModel(uint32_t model) = 0;
	virtual ModelDims modelDimensions(uint32_t model) = 0;

	// ---- Props (the voxel renderer) ----
	// Creates a frozen, collidable, invincible prop whose model origin sits at `pos`. Returns 0 on failure.
	virtual Handle createProp(uint32_t model, Vec3 pos) = 0;
	virtual void destroyProp(Handle h) = 0;
	virtual bool propExists(Handle h) = 0;
	virtual void moveProp(Handle h, Vec3 pos) = 0;
	virtual void setPropActive(Handle h, bool active) = 0;  // visible + collidable, or hidden + non-collidable
	virtual void setPropVariation(Handle h, int variation) = 0;

	// ---- Player and world queries ----
	virtual PlayerSample samplePlayer() = 0;
	virtual RayHit castRay(Vec3 from, Vec3 to, Handle ignore) = 0;
	virtual float weaponDamage(uint32_t weapon) = 0;

	// ---- UI / environment ----
	virtual void notify(const char* text) = 0;
	virtual void drawText(float x, float y, float scale, const char* text) = 0;
	virtual bool onlineSession() = 0;  // true in GTA Online; the mod must stay inert there
};

}  // namespace opt
