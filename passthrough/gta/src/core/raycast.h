// Voxel ray traversal (Amanatides & Woo DDA) over the mirrored voxel shell, in Minecraft space. Used for contact
// queries and as an engine-independent cross-check of GTA's own ray results.
#pragma once

#include "common.h"
#include "voxel_store.h"

namespace opt {

struct VoxelHit {
	bool hit = false;
	int32_t x = 0, y = 0, z = 0;
	uint8_t face = 0;  // face of the voxel that the ray entered through (ocp::Face)
	double t = 0;      // distance along the (normalised) ray
};

// Walks voxels along the ray and returns the first non-empty one within maxDist.
VoxelHit raycastVoxels(const VoxelStore& store, Vec3 origin, Vec3 dir, double maxDist);

// Finds the voxel an axis-aligned box (Minecraft space) is pressing into while moving along `dir`: among occupied
// voxels overlapping the box expanded by `skin`, picks the one nearest the box's leading face.
VoxelHit findContactVoxel(const VoxelStore& store, Vec3 boxMin, Vec3 boxMax, Vec3 dir, double skin);

}  // namespace opt
