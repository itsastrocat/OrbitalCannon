#include "raycast.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "transform.h"
#include "../../../protocol/orbital_ipc.h"

namespace opt {

VoxelHit raycastVoxels(const VoxelStore& store, Vec3 origin, Vec3 dir, double maxDist) {
	VoxelHit out;
	const double len = length(dir);
	if (len < 1e-12 || maxDist <= 0) return out;
	dir = dir * (1.0 / len);

	int32_t x = Transform::floorToInt(origin.x), y = Transform::floorToInt(origin.y), z = Transform::floorToInt(origin.z);
	const int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
	constexpr double kInf = std::numeric_limits<double>::infinity();
	const double dX = std::fabs(dir.x) < 1e-12 ? kInf : std::fabs(1.0 / dir.x);
	const double dY = std::fabs(dir.y) < 1e-12 ? kInf : std::fabs(1.0 / dir.y);
	const double dZ = std::fabs(dir.z) < 1e-12 ? kInf : std::fabs(1.0 / dir.z);
	auto firstBoundary = [](double o, int32_t cell, int step, double d) {
		if (d == kInf) return kInf;
		return (step > 0 ? (cell + 1 - o) : (o - cell)) * d;
	};
	double tMaxX = firstBoundary(origin.x, x, stepX, dX);
	double tMaxY = firstBoundary(origin.y, y, stepY, dY);
	double tMaxZ = firstBoundary(origin.z, z, stepZ, dZ);

	// Origin inside a voxel: report it with the face opposite the dominant direction.
	if (store.get(x, y, z) != 0) {
		out.hit = true;
		out.x = x;
		out.y = y;
		out.z = z;
		out.t = 0;
		const double ax = std::fabs(dir.x), ay = std::fabs(dir.y), az = std::fabs(dir.z);
		if (ay >= ax && ay >= az) out.face = dir.y > 0 ? ocp::kDown : ocp::kUp;
		else if (az >= ax) out.face = dir.z > 0 ? ocp::kNorth : ocp::kSouth;
		else out.face = dir.x > 0 ? ocp::kWest : ocp::kEast;
		return out;
	}

	for (int guard = 0; guard < 4096; ++guard) {
		double t;
		uint8_t face;
		if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
			t = tMaxX;
			x += stepX;
			tMaxX += dX;
			face = stepX > 0 ? ocp::kWest : ocp::kEast;
		} else if (tMaxY <= tMaxZ) {
			t = tMaxY;
			y += stepY;
			tMaxY += dY;
			face = stepY > 0 ? ocp::kDown : ocp::kUp;
		} else {
			t = tMaxZ;
			z += stepZ;
			tMaxZ += dZ;
			face = stepZ > 0 ? ocp::kNorth : ocp::kSouth;
		}
		if (t > maxDist) return out;
		if (store.get(x, y, z) != 0) {
			out.hit = true;
			out.x = x;
			out.y = y;
			out.z = z;
			out.face = face;
			out.t = t;
			return out;
		}
	}
	return out;
}

VoxelHit findContactVoxel(const VoxelStore& store, Vec3 boxMin, Vec3 boxMax, Vec3 dir, double skin) {
	VoxelHit best;
	const Vec3 d = normalized(dir);
	const Vec3 centre = (boxMin + boxMax) * 0.5;
	const Vec3 half = (boxMax - boxMin) * 0.5;
	// Point on the box surface in the direction of travel: the "leading" point.
	const Vec3 lead = {centre.x + (d.x > 0.3 ? half.x : d.x < -0.3 ? -half.x : 0),
	                   centre.y + (d.y > 0.3 ? half.y : d.y < -0.3 ? -half.y : 0),
	                   centre.z + (d.z > 0.3 ? half.z : d.z < -0.3 ? -half.z : 0)};
	const int32_t x0 = Transform::floorToInt(boxMin.x - skin), x1 = Transform::floorToInt(boxMax.x + skin);
	const int32_t y0 = Transform::floorToInt(boxMin.y - skin), y1 = Transform::floorToInt(boxMax.y + skin);
	const int32_t z0 = Transform::floorToInt(boxMin.z - skin), z1 = Transform::floorToInt(boxMax.z + skin);
	double bestScore = std::numeric_limits<double>::infinity();
	for (int32_t x = x0; x <= x1; ++x)
		for (int32_t y = y0; y <= y1; ++y)
			for (int32_t z = z0; z <= z1; ++z) {
				if (store.get(x, y, z) == 0) continue;
				const Vec3 c{x + 0.5, y + 0.5, z + 0.5};
				// Skip voxels the box is moving away from.
				if (dot(c - centre, d) < -0.5) continue;
				const double score = length(c - lead);
				if (score < bestScore) {
					bestScore = score;
					best.hit = true;
					best.x = x;
					best.y = y;
					best.z = z;
					best.t = score;
				}
			}
	if (best.hit) {
		// Face the box struck: the one whose outward normal best opposes the motion.
		const double ax = std::fabs(d.x), ay = std::fabs(d.y), az = std::fabs(d.z);
		if (ay >= ax && ay >= az) best.face = d.y > 0 ? ocp::kDown : ocp::kUp;
		else if (az >= ax) best.face = d.z > 0 ? ocp::kNorth : ocp::kSouth;
		else best.face = d.x > 0 ? ocp::kWest : ocp::kEast;
	}
	return best;
}

}  // namespace opt
