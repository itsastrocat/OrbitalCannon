#include "natives.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#include "native_call.h"
#include "native_hashes.h"
#include "../core/hash.h"
#include "../../../protocol/orbital_ipc.h"

namespace shv {

using namespace opt;
namespace n = shv::nat;

namespace {
constexpr double kPi = 3.14159265358979323846;
// Shape-test flags: 1 world, 2 vehicles, 4 simple peds, 8 peds, 16 objects. Props are objects; the rest occlude them.
constexpr int32_t kTraceFlags = 1 | 2 | 4 | 8 | 16;

inline Vec3 toVec(const ScriptVec3& v) { return {v.x, v.y, v.z}; }
}  // namespace

uint64_t ShvGame::nowMicros() {
	return static_cast<uint64_t>(
		std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
}

uint32_t ShvGame::joaat(const char* s) { return joaatHash(s); }

// ---- models ----------------------------------------------------------------------------------------------------

bool ShvGame::modelExists(uint32_t model) {
	return call<int32_t>(n::IS_MODEL_IN_CDIMAGE, model) != 0 && call<int32_t>(n::IS_MODEL_VALID, model) != 0;
}
void ShvGame::requestModel(uint32_t model) { call(n::REQUEST_MODEL, model); }
bool ShvGame::modelLoaded(uint32_t model) { return call<int32_t>(n::HAS_MODEL_LOADED, model) != 0; }
void ShvGame::releaseModel(uint32_t model) { call(n::SET_MODEL_AS_NO_LONGER_NEEDED, model); }

ModelDims ShvGame::modelDimensions(uint32_t model) {
	ScriptVec3 mn{}, mx{};
	call(n::GET_MODEL_DIMENSIONS, model, &mn, &mx);
	ModelDims d;
	d.min = toVec(mn);
	d.max = toVec(mx);
	d.ok = d.max.x > d.min.x && d.max.y > d.min.y && d.max.z > d.min.z;
	return d;
}

ModelDims ShvGame::cachedDims(uint32_t model) {
	auto it = dimsCache_.find(model);
	if (it != dimsCache_.end()) return it->second;
	const ModelDims d = modelDimensions(model);
	if (d.ok) dimsCache_[model] = d;  // do not cache failures: the model may simply not be streamed in yet
	return d;
}

// ---- props -----------------------------------------------------------------------------------------------------

Handle ShvGame::createProp(uint32_t model, Vec3 pos) {
	const int32_t h = call<int32_t>(n::CREATE_OBJECT_NO_OFFSET, model, static_cast<float>(pos.x), static_cast<float>(pos.y),
	                                static_cast<float>(pos.z), false, false, false);
	if (h == 0) return 0;
	call(n::FREEZE_ENTITY_POSITION, h, true);               // a voxel never moves on its own
	call(n::SET_ENTITY_COLLISION, h, true, false);          // solid for the player, peds, vehicles and bullets
	call(n::SET_ENTITY_LOAD_COLLISION_FLAG, h, true);       // keep its collision resident even when far from the camera
	call(n::SET_ENTITY_INVINCIBLE, h, true);
	call(n::SET_ENTITY_CAN_BE_DAMAGED, h, false);
	call(n::SET_ENTITY_LOD_DIST, h, 160);
	return h;
}

void ShvGame::destroyProp(Handle h) {
	int32_t handle = h;  // DELETE_OBJECT takes the handle by pointer and zeroes it
	call(n::DELETE_OBJECT, &handle);
}

bool ShvGame::propExists(Handle h) { return call<int32_t>(n::DOES_ENTITY_EXIST, h) != 0; }

void ShvGame::moveProp(Handle h, Vec3 pos) {
	call(n::SET_ENTITY_COORDS_NO_OFFSET, h, static_cast<float>(pos.x), static_cast<float>(pos.y), static_cast<float>(pos.z), false,
	     false, false);
}

void ShvGame::setPropActive(Handle h, bool active) {
	call(n::SET_ENTITY_VISIBLE, h, active, false);
	call(n::SET_ENTITY_COLLISION, h, active, false);
}

void ShvGame::setPropVariation(Handle h, int variation) { call(n::SET_OBJECT_TEXTURE_VARIATION, h, static_cast<int32_t>(variation)); }

// ---- player and world ------------------------------------------------------------------------------------------

PlayerSample ShvGame::samplePlayer() {
	PlayerSample s;
	const int32_t ped = call<int32_t>(n::PLAYER_PED_ID);
	if (ped == 0) return s;
	s.ped = ped;
	const bool dead = call<int32_t>(n::IS_ENTITY_DEAD, ped) != 0;
	s.valid = !dead;

	const bool inVehicle = call<int32_t>(n::IS_PED_IN_ANY_VEHICLE, ped, false) != 0;
	const int32_t vehicle = inVehicle ? call<int32_t>(n::GET_VEHICLE_PED_IS_IN, ped, false) : 0;
	const int32_t entity = vehicle ? vehicle : ped;
	s.vehicle = vehicle;

	const ScriptVec3 pos = call<ScriptVec3>(n::GET_ENTITY_COORDS, entity, true);
	const ScriptVec3 vel = call<ScriptVec3>(n::GET_ENTITY_VELOCITY, entity);
	const float heading = call<float>(n::GET_ENTITY_HEADING, entity);
	const uint32_t model = call<uint32_t>(n::GET_ENTITY_MODEL, entity);

	// Hitbox: the model's bounding box rotated by the heading (GTA heading rotates counter-clockwise from +Y), reduced
	// to the axis-aligned box that encloses it. The feet plane is the bottom of the model box.
	double minX = -0.35, maxX = 0.35, minY = -0.35, maxY = 0.35, minZ = -1.0, maxZ = 0.8;
	const ModelDims d = cachedDims(model);
	if (d.ok) {
		minX = d.min.x;
		maxX = d.max.x;
		minY = d.min.y;
		maxY = d.max.y;
		minZ = d.min.z;
		maxZ = d.max.z;
	}
	const double th = heading * kPi / 180.0, c = std::cos(th), sn = std::sin(th);
	double lox = 1e9, hix = -1e9, loy = 1e9, hiy = -1e9;
	for (const double cx : {minX, maxX}) {
		for (const double cy : {minY, maxY}) {
			const double rx = cx * c - cy * sn, ry = cx * sn + cy * c;
			lox = std::min(lox, rx);
			hix = std::max(hix, rx);
			loy = std::min(loy, ry);
			hiy = std::max(hiy, ry);
		}
	}
	s.feet = {pos.x, pos.y, pos.z + minZ};
	s.velocity = toVec(vel);
	s.heading = heading;
	s.hitMin[0] = static_cast<float>(lox);
	s.hitMin[1] = static_cast<float>(loy);
	s.hitMin[2] = 0.0f;
	s.hitMax[0] = static_cast<float>(hix);
	s.hitMax[1] = static_cast<float>(hiy);
	s.hitMax[2] = static_cast<float>(maxZ - minZ);

	// Gameplay camera: position and a forward vector from its Euler angles (yaw about +Z, pitch about the X axis).
	const ScriptVec3 cam = call<ScriptVec3>(n::GET_GAMEPLAY_CAM_COORD);
	const ScriptVec3 rot = call<ScriptVec3>(n::GET_GAMEPLAY_CAM_ROT, 2);
	const double pitch = rot.x * kPi / 180.0, yaw = rot.z * kPi / 180.0;
	s.camPos = toVec(cam);
	s.camDir = {-std::sin(yaw) * std::cos(pitch), std::cos(yaw) * std::cos(pitch), std::sin(pitch)};

	s.weapon = call<uint32_t>(n::GET_SELECTED_PED_WEAPON, ped);
	s.shooting = call<int32_t>(n::IS_PED_SHOOTING, ped) != 0;
	if (s.shooting) {
		ScriptVec3 impact{};
		if (call<int32_t>(n::GET_PED_LAST_WEAPON_IMPACT_COORD, ped, &impact) != 0) {
			s.hasImpact = true;
			s.impact = toVec(impact);
		}
	}
	if (vehicle) {
		s.vehicleModel = model;
		s.vehicleSpeed = call<float>(n::GET_ENTITY_SPEED, vehicle);
	}
	s.flags = 0;
	if (call<int32_t>(n::IS_PED_ON_FOOT, ped) != 0) s.flags |= ocp::kPfOnFoot;
	if (inVehicle) s.flags |= ocp::kPfInVehicle;
	if (s.shooting) s.flags |= ocp::kPfShooting;
	if (dead) s.flags |= ocp::kPfDead;
	if (call<int32_t>(n::IS_PED_RAGDOLL, ped) != 0) s.flags |= ocp::kPfRagdoll;
	return s;
}

RayHit ShvGame::castRay(Vec3 from, Vec3 to, Handle ignore) {
	const int32_t probe = call<int32_t>(n::START_EXPENSIVE_SYNCHRONOUS_SHAPE_TEST_LOS_PROBE, static_cast<float>(from.x),
	                                    static_cast<float>(from.y), static_cast<float>(from.z), static_cast<float>(to.x),
	                                    static_cast<float>(to.y), static_cast<float>(to.z), kTraceFlags, ignore, 7);
	int32_t hit = 0, entity = 0;
	ScriptVec3 end{}, normal{};
	call<int32_t>(n::GET_SHAPE_TEST_RESULT, probe, &hit, &end, &normal, &entity);
	RayHit r;
	if (hit == 0) return r;
	r.hit = true;
	r.point = toVec(end);
	r.normal = normalized(toVec(normal));
	r.entity = entity;
	return r;
}

float ShvGame::weaponDamage(uint32_t weapon) {
	const float dmg = call<float>(n::GET_WEAPON_DAMAGE, weapon, 0u);
	return dmg > 0.0f ? dmg : 20.0f;
}

// ---- UI --------------------------------------------------------------------------------------------------------

void ShvGame::notify(const char* text) {
	char buf[100];
	std::strncpy(buf, text, sizeof buf - 1);  // a text component holds at most 99 bytes
	buf[sizeof buf - 1] = '\0';
	call(n::BEGIN_TEXT_COMMAND_THEFEED_POST, "STRING");
	call(n::ADD_TEXT_COMPONENT_SUBSTRING_PLAYER_NAME, static_cast<const char*>(buf));
	call<int32_t>(n::END_TEXT_COMMAND_THEFEED_POST_TICKER, false, false);
}

void ShvGame::drawText(float x, float y, float scale, const char* text) {
	char buf[100];
	std::strncpy(buf, text, sizeof buf - 1);
	buf[sizeof buf - 1] = '\0';
	call(n::SET_TEXT_FONT, 0);
	call(n::SET_TEXT_SCALE, 0.0f, scale);
	call(n::SET_TEXT_COLOUR, 255, 255, 255, 230);
	call(n::SET_TEXT_OUTLINE);
	call(n::BEGIN_TEXT_COMMAND_DISPLAY_TEXT, "STRING");
	call(n::ADD_TEXT_COMPONENT_SUBSTRING_PLAYER_NAME, static_cast<const char*>(buf));
	call(n::END_TEXT_COMMAND_DISPLAY_TEXT, x, y, 0);
}

bool ShvGame::onlineSession() { return call<int32_t>(n::NETWORK_IS_GAME_IN_PROGRESS) != 0; }

}  // namespace shv
