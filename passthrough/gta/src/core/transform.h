// Coordinate mapping between Minecraft and GTA V.
//
// Minecraft: +X east, +Y up, +Z south.   GTA V: +X east, +Y north, +Z up.
// The mapping (x, y, z)_mc -> (x, -z, y)_gta is a proper rotation (determinant +1), so voxels are never mirrored.
// A single anchor pair pins one Minecraft point to one GTA point; everything else follows at 1 block = 1 metre.
//
// This file is mirrored by minecraft/.../world/WorldTransform.java; the cross-language E2E test checks both agree.
#pragma once

#include <cmath>

#include "common.h"
#include "../../../protocol/orbital_ipc.h"

namespace opt {

struct Transform {
	bool valid = false;
	Vec3 anchorMc;
	Vec3 anchorGta;

	Vec3 toGta(Vec3 mc) const {
		return {anchorGta.x + (mc.x - anchorMc.x), anchorGta.y - (mc.z - anchorMc.z), anchorGta.z + (mc.y - anchorMc.y)};
	}
	Vec3 toMc(Vec3 g) const {
		return {anchorMc.x + (g.x - anchorGta.x), anchorMc.y + (g.z - anchorGta.z), anchorMc.z - (g.y - anchorGta.y)};
	}
	// Direction/offset vectors (no translation).
	static Vec3 dirToMc(Vec3 d) { return {d.x, d.z, -d.y}; }
	static Vec3 dirToGta(Vec3 d) { return {d.x, -d.z, d.y}; }

	Vec3 voxelCenterGta(int32_t x, int32_t y, int32_t z) const { return toGta({x + 0.5, y + 0.5, z + 0.5}); }

	static int32_t floorToInt(double v) { return static_cast<int32_t>(std::floor(v)); }

	// Maps a GTA surface normal to the Minecraft face it points out of. Picks the dominant axis.
	static uint8_t faceFromGtaNormal(Vec3 gtaNormal) {
		const Vec3 n = dirToMc(gtaNormal);
		const double ax = std::fabs(n.x), ay = std::fabs(n.y), az = std::fabs(n.z);
		if (ay >= ax && ay >= az) return n.y > 0 ? ocp::kUp : ocp::kDown;
		if (az >= ax) return n.z > 0 ? ocp::kSouth : ocp::kNorth;
		return n.x > 0 ? ocp::kEast : ocp::kWest;
	}

	static void faceNormal(uint8_t face, int& dx, int& dy, int& dz) {
		static constexpr int N[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
		face = face > 5 ? 0 : face;
		dx = N[face][0];
		dy = N[face][1];
		dz = N[face][2];
	}
};

}  // namespace opt
