package com.orbitalcannon.passthrough.world;

/**
 * Coordinate mapping between Minecraft and GTA V, mirrored by {@code passthrough/gta/src/core/transform.h}.
 *
 * <p>Minecraft: +X east, +Y up, +Z south. GTA V: +X east, +Y north, +Z up. The map {@code (x, y, z)_mc -> (x, -z, y)_gta}
 * is a proper rotation (determinant +1), so voxels are never mirrored. One anchor pair pins a Minecraft point to a GTA
 * point; everything else follows at one block per metre.
 */
public final class WorldTransform {
	public boolean valid;
	public double amx, amy, amz; // anchor in Minecraft space
	public double agx, agy, agz; // anchor in GTA space

	public void setMcAnchor(double x, double y, double z) {
		amx = x;
		amy = y;
		amz = z;
	}

	public void setGtaAnchor(double x, double y, double z) {
		agx = x;
		agy = y;
		agz = z;
	}

	public double mcX(double gx) {
		return amx + (gx - agx);
	}

	public double mcY(double gz) {
		return amy + (gz - agz);
	}

	public double mcZ(double gy) {
		return amz - (gy - agy);
	}

	public double gtaX(double x) {
		return agx + (x - amx);
	}

	public double gtaY(double z) {
		return agy - (z - amz);
	}

	public double gtaZ(double y) {
		return agz + (y - amy);
	}
}
