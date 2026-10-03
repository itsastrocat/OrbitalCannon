#include "interaction.h"

#include <algorithm>
#include <cmath>

#include "log.h"
#include "raycast.h"

namespace opt {

Interaction::Interaction(GameApi& game, Bridge& bridge, PropManager& props, const VoxelStore& store, const Transform& tf,
                         InteractionConfig cfg)
	: game_(game), bridge_(bridge), props_(props), store_(store), tf_(tf), cfg_(cfg) {}

Interaction::Target Interaction::trace(Vec3 from, Vec3 to, Handle ignore) {
	const RayHit h = game_.castRay(from, to, ignore);
	if (!h.hit) {
		++counters_.probeMisses;
		return {};
	}
	Target t;
	if (!props_.voxelOfEntity(h.entity, t.x, t.y, t.z)) return {};  // something else (a wall, a car) is in front
	t.ok = true;
	t.face = Transform::faceFromGtaNormal(h.normal);
	t.point = h.point;
	return t;
}

Interaction::Target Interaction::crosshair(const PlayerSample& p) {
	if (!p.valid || length(p.camDir) < 0.5) return {};
	// In third person the camera sits behind the player; measure reach from the player, not from the lens.
	const double camBack = length(p.camPos - (p.feet + Vec3{0, 0, 1.5}));
	return trace(p.camPos, p.camPos + p.camDir * (camBack + cfg_.reach), p.vehicle ? p.vehicle : p.ped);
}

bool Interaction::mine(const PlayerSample& p) {
	if (!tf_.valid || !bridge_.mcAlive()) return false;
	const Target t = crosshair(p);
	if (!t.ok) return false;
	if (!bridge_.sendBreak(ocp::kSrcTool, t.face, t.x, t.y, t.z, cfg_.toolDamage, 0)) return false;
	++counters_.breaksSent;
	return true;
}

bool Interaction::place(const PlayerSample& p, uint16_t paletteId) {
	if (!tf_.valid || !bridge_.mcAlive() || paletteId == 0) return false;
	const Target t = crosshair(p);
	if (!t.ok) return false;
	if (!bridge_.sendPlace(t.face, paletteId, t.x, t.y, t.z)) return false;
	++counters_.placesSent;
	return true;
}

void Interaction::handleShot(const PlayerSample& p, uint64_t nowUs) {
	if (!weaponsInit_) {
		for (const char* w : {"WEAPON_RPG", "WEAPON_GRENADELAUNCHER", "WEAPON_HOMINGLAUNCHER", "WEAPON_COMPACTLAUNCHER",
		                      "WEAPON_RAILGUN", "WEAPON_FIREWORK", "WEAPON_EMPLAUNCHER"}) {
			explosive_.insert(game_.joaat(w));
		}
		weaponsInit_ = true;
	}
	if (!p.shooting || !p.hasImpact) return;
	++counters_.shotsSeen;
	if (nowUs - lastShotUs_ < cfg_.shotMinIntervalMs * 1000ull) return;

	const Vec3 toImpact = p.impact - p.camPos;
	const double dist = length(toImpact);
	if (dist < 0.2 || dist > cfg_.bulletRange) return;
	// Probe a little past the reported impact so the ray is certain to cross the surface the bullet struck.
	const Vec3 to = p.impact + normalized(toImpact) * 0.35;
	// Probes ignore one entity: the vehicle when driving (so shots do not hit the player's own car), the ped otherwise.
	const Target t = trace(p.camPos, to, p.vehicle ? p.vehicle : p.ped);
	if (!t.ok || length(t.point - p.impact) > cfg_.impactMatchTolerance) return;  // the bullet hit something else
	++counters_.shotsMatched;

	const bool boom = explosive_.count(p.weapon) != 0;
	const uint8_t source = boom ? ocp::kSrcExplosive : ocp::kSrcBullet;
	const float damage = boom ? cfg_.explosiveDamage : game_.weaponDamage(p.weapon);
	if (bridge_.sendBreak(source, t.face, t.x, t.y, t.z, damage, p.weapon)) {
		lastShotUs_ = nowUs;
		++counters_.breaksSent;
	}
}

void Interaction::handleCollision(const PlayerSample& p, uint64_t nowUs) {
	const Handle entity = p.vehicle ? p.vehicle : p.ped;
	const double speed = length(p.velocity);
	if (!havePrev_ || entity != prevEntity_) {
		havePrev_ = true;
		prevEntity_ = entity;
		prevVel_ = p.velocity;
		prevSpeed_ = speed;
		return;
	}
	const double drop = prevSpeed_ - speed;
	const bool vehicle = p.vehicle != 0;
	const double threshold = vehicle ? cfg_.collisionMinDeltaV : cfg_.pedCollisionMinDeltaV;
	if (drop >= threshold && nowUs >= collisionCooldownUntilUs_) {
		// Hitbox corners are GTA-axis offsets from the feet; the transform is a pure rotation+translation, so the
		// image of an axis-aligned box is still axis-aligned and min/max per axis is exact.
		const Vec3 a = tf_.toMc(p.feet + Vec3{p.hitMin[0], p.hitMin[1], p.hitMin[2]});
		const Vec3 b = tf_.toMc(p.feet + Vec3{p.hitMax[0], p.hitMax[1], p.hitMax[2]});
		const Vec3 lo{std::min(a.x, b.x), std::min(a.y, b.y), std::min(a.z, b.z)};
		const Vec3 hi{std::max(a.x, b.x), std::max(a.y, b.y), std::max(a.z, b.z)};
		const VoxelHit hit = findContactVoxel(store_, lo, hi, Transform::dirToMc(prevVel_), 0.4);
		if (hit.hit) {
			ocp::CollisionEvent e{};
			e.kind = vehicle ? ocp::kColVehicle : ocp::kColPed;
			int dx, dy, dz;
			Transform::faceNormal(hit.face, dx, dy, dz);
			// hit.face is the voxel face the entity struck, so its outward normal already points from the voxel toward
			// the entity, which is the convention documented in the protocol.
			e.nx = static_cast<int8_t>(dx);
			e.ny = static_cast<int8_t>(dy);
			e.nz = static_cast<int8_t>(dz);
			e.x = hit.x;
			e.y = hit.y;
			e.z = hit.z;
			e.speed = static_cast<float>(prevSpeed_);
			e.delta_v = static_cast<float>(drop);
			e.mass = vehicle ? cfg_.vehicleMassKg : cfg_.pedMassKg;
			if (bridge_.sendCollision(e)) {
				++counters_.collisionsSent;
				collisionCooldownUntilUs_ = nowUs + cfg_.collisionCooldownMs * 1000ull;
			}
		}
	}
	prevVel_ = p.velocity;
	prevSpeed_ = speed;
}

void Interaction::frame(const PlayerSample& p, uint64_t nowUs) {
	if (!p.valid || !tf_.valid || !bridge_.mcAlive()) {
		havePrev_ = false;
		return;
	}
	if (cfg_.shots) handleShot(p, nowUs);
	if (cfg_.collisions) handleCollision(p, nowUs);
}

}  // namespace opt
