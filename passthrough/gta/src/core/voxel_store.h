// GTA-side mirror of the visible voxel shell streamed from Minecraft.
//
// Only voxels that Minecraft decided are visible (exposed to at least one non-opaque neighbour) are stored, as
// 16-bit palette ids in dense 16x16x16 sections. Every mutation is also appended to a change list that the prop
// manager consumes once per frame, so rendering reacts to exactly what changed and never rescans the world.
#pragma once

#include <cstdint>
#include <memory>
#include <unordered_map>
#include <vector>

#include "common.h"
#include "../../../protocol/orbital_ipc.h"

namespace opt {

struct VoxelChange {
	int32_t x, y, z;
	uint16_t oldId, newId;
	bool urgent;  // caused by this client's own action: render before everything else
};

class VoxelStore {
public:
	struct Section {
		int32_t sx = 0, sy = 0, sz = 0;
		uint32_t version = 0;
		uint32_t count = 0;  // non-zero voxels
		uint16_t ids[4096] = {};
	};

	void clear();

	// Applies one SECTION_DATA record. With kSecReplace the section is emptied first (emitting removals).
	// Returns false if the record is malformed.
	bool applySectionData(const uint8_t* payload, uint32_t len);
	void unloadSection(int32_t sx, int32_t sy, int32_t sz);
	// Applies one VOXEL_UPDATES record. Returns false if malformed.
	bool applyVoxelUpdates(const uint8_t* payload, uint32_t len);

	void set(int32_t x, int32_t y, int32_t z, uint16_t id, bool urgent);
	uint16_t get(int32_t x, int32_t y, int32_t z) const;

	std::vector<VoxelChange>& changes() { return changes_; }
	size_t sectionCount() const { return sections_.size(); }
	size_t voxelCount() const { return voxels_; }

	template <class F>
	void forEachSection(F&& f) const {
		for (const auto& kv : sections_) f(*kv.second);
	}

private:
	Section* findSection(int32_t sx, int32_t sy, int32_t sz) const;
	Section& getOrCreate(int32_t sx, int32_t sy, int32_t sz);
	void setInSection(Section& s, uint32_t local, uint16_t id, bool urgent);

	std::unordered_map<uint64_t, std::unique_ptr<Section>> sections_;
	std::vector<VoxelChange> changes_;
	size_t voxels_ = 0;

	// One-entry cache: raycasts and neighbour queries hit the same section many times in a row.
	mutable uint64_t cacheKey_ = ~0ull;
	mutable Section* cacheSection_ = nullptr;
};

}  // namespace opt
