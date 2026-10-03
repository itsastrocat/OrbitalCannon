// Shared small types and helpers for the GTA V passthrough. Deliberately free of any Windows or ScriptHookV include
// so every module that uses it can be unit tested on any platform.
#pragma once

#include <cmath>
#include <cstdint>

namespace opt {

using Handle = int32_t;  // GTA entity handle; 0 = none

struct Vec3 {
	double x = 0, y = 0, z = 0;
};
inline Vec3 operator+(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline Vec3 operator-(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline Vec3 operator*(Vec3 a, double s) { return {a.x * s, a.y * s, a.z * s}; }
inline double dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline double length(Vec3 a) { return std::sqrt(dot(a, a)); }
inline Vec3 normalized(Vec3 a) {
	const double l = length(a);
	return l > 1e-12 ? a * (1.0 / l) : Vec3{};
}

// Voxel keys pack Minecraft block coordinates into 64 bits: x:26 | z:26 | y:12 (y covers -2048..2047).
inline uint64_t voxelKey(int32_t x, int32_t y, int32_t z) {
	return (static_cast<uint64_t>(static_cast<uint32_t>(x) & 0x3FFFFFFu) << 38) |
	       (static_cast<uint64_t>(static_cast<uint32_t>(z) & 0x3FFFFFFu) << 12) |
	       static_cast<uint64_t>(static_cast<uint32_t>(y) & 0xFFFu);
}
inline void unpackVoxelKey(uint64_t k, int32_t& x, int32_t& y, int32_t& z) {
	x = static_cast<int32_t>(static_cast<uint32_t>(k >> 38) << 6) >> 6;
	z = static_cast<int32_t>(static_cast<uint32_t>((k >> 12) & 0x3FFFFFFu) << 6) >> 6;
	y = static_cast<int32_t>(static_cast<uint32_t>(k & 0xFFFu) << 20) >> 20;
}

// Section keys: sx:22 | sz:22 | sy:20.
inline uint64_t sectionKey(int32_t sx, int32_t sy, int32_t sz) {
	return (static_cast<uint64_t>(static_cast<uint32_t>(sx) & 0x3FFFFFu) << 42) |
	       (static_cast<uint64_t>(static_cast<uint32_t>(sz) & 0x3FFFFFu) << 20) |
	       static_cast<uint64_t>(static_cast<uint32_t>(sy) & 0xFFFFFu);
}

inline int32_t floorDiv16(int32_t v) { return v >> 4; }  // arithmetic shift == floor for negatives

}  // namespace opt
