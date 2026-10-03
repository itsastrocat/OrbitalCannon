// Turns the voxel shell into live GTA props under a hard entity budget.
//
// GTA V can sustain on the order of a couple of thousand script-created objects, so the manager never tries to
// mirror the world. It keeps the nearest voxels to the player alive and treats everything else as a cache:
//   * nearest-first spawn queue (min-heap), plus an urgent FIFO for blocks the player just placed
//   * hard cap on live + parked entities, evicting the farthest prop when something nearer needs the slot
//   * pooling: a retired prop is parked (hidden, non-collidable) and re-used for the next voxel of the same model,
//     which costs two natives instead of a full create/destroy
//   * per-frame spawn/retire counts and a wall-clock budget so streaming can never stall a frame
//   * hysteresis between spawn and retire radius so props at the boundary never flicker
//   * self-healing: a few props per frame are checked for existence and respawned if the engine removed them
#pragma once

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "common.h"
#include "flat_map.h"
#include "game_api.h"
#include "palette.h"
#include "transform.h"
#include "voxel_store.h"

namespace opt {

struct PropConfig {
	uint32_t maxProps = 1500;          // hard cap on live + parked entities
	float spawnRadius = 22.0f;         // blocks
	float retireMargin = 4.0f;         // retire radius = spawnRadius + margin
	uint32_t maxSpawnsPerFrame = 48;
	uint32_t maxRetiresPerFrame = 96;
	uint32_t frameBudgetUs = 1500;     // wall-clock budget for the spawn loop
	float rescanDistance = 2.5f;       // re-rank candidates after the player moves this far
	uint32_t rescanIntervalMs = 1000;  // ... or at least this often
	uint32_t validatePerFrame = 12;
	uint32_t parkedPoolMax = 256;
	float unitCubeTolerance = 0.15f;   // warn when a model deviates from 1 m by more than this
};

struct PropStats {
	uint32_t live = 0, parked = 0, queued = 0;
	uint32_t spawnedLastFrame = 0;
	uint64_t created = 0, recycled = 0, destroyed = 0, createFailures = 0, respawned = 0, evicted = 0;
	uint32_t modelsMissing = 0;
};

class PropManager {
public:
	PropManager(GameApi& game, VoxelStore& store, const StyleSource& styles, PropConfig cfg = {});

	void setConfig(const PropConfig& cfg) { cfg_ = cfg; }
	const PropConfig& config() const { return cfg_; }

	// Called when the anchor changes: every prop is in the wrong place, so park them all and re-rank.
	void onTransformChanged();
	// Per-frame pump. playerGta is the player's current GTA position.
	void tick(const Transform& tf, Vec3 playerGta);
	// Destroys every entity and releases models. Safe to call repeatedly.
	void shutdown();

	// entity -> voxel, used to resolve raycast hits against props.
	bool voxelOfEntity(Handle h, int32_t& x, int32_t& y, int32_t& z) const;
	bool isLive(int32_t x, int32_t y, int32_t z) const { return liveByKey_.contains(voxelKey(x, y, z)); }
	Handle handleAt(int32_t x, int32_t y, int32_t z) const;

	const PropStats& stats() const { return stats_; }

private:
	struct Slot {
		Handle handle = 0;
		uint64_t key = 0;
		uint32_t model = 0;
		uint16_t id = 0;
		bool live = false;
	};
	struct Cand {
		float d2;
		uint64_t key;
	};
	struct ModelInfo {
		bool requested = false, loaded = false, missing = false;
		uint32_t checkedTick = 0;  // tick in which HAS_MODEL_LOADED last said "not yet"; asked at most once per frame
		Vec3 centerOffset;
	};
	struct EvictEntry {
		float d2;
		uint32_t slot;
		uint64_t key;  // guards against the slot having been recycled for another voxel since the scan
	};
	enum class SpawnResult { kSpawned, kDeferred, kNoCapacity, kFailed, kDropped };

	void processChanges(Vec3 pm);
	void maybeRescan(Vec3 pm, uint64_t nowUs);
	void rescan(Vec3 pm);
	void processRetireQueue();
	void spawnLoop(const Transform& tf, Vec3 pm, uint64_t startUs);
	void validateSlice(const Transform& tf);

	ModelInfo* ensureModel(uint32_t model);
	SpawnResult trySpawn(const Transform& tf, uint64_t key, float d2);
	bool evictFarthest(float beyondD2);
	void retire(uint32_t slot);
	bool popParked(uint32_t model, Handle& out);
	void destroyOneParked();
	void enqueue(uint64_t key, float d2, bool urgent);
	uint32_t allocSlot();

	GameApi& game_;
	VoxelStore& store_;
	const StyleSource& styles_;
	PropConfig cfg_;
	PropStats stats_;

	std::vector<Slot> slots_;
	std::vector<uint32_t> freeSlots_;
	FlatMap64<uint32_t> liveByKey_;
	FlatMap64<uint32_t> slotByHandle_;
	std::unordered_map<uint32_t, std::vector<Handle>> parked_;
	uint32_t parkedCount_ = 0;
	std::unordered_map<uint32_t, ModelInfo> models_;

	std::vector<Cand> heap_;              // min-heap on d2
	std::vector<uint64_t> urgent_;        // FIFO of voxel keys
	size_t urgentHead_ = 0;
	FlatMap64<uint8_t> queued_;
	std::vector<uint32_t> retireQueue_;  // slots to retire because they drifted out of range
	size_t retireHead_ = 0;
	std::vector<EvictEntry> evictList_;  // ascending d2; back() is the farthest live prop

	Vec3 curPm_;  // player position in Minecraft space, refreshed every tick
	Vec3 lastScanPos_;
	uint64_t lastScanUs_ = 0;
	bool forceRescan_ = true;
	uint32_t validateCursor_ = 0;
	uint32_t tickSerial_ = 1;
	uint32_t consecutiveFailures_ = 0;
	uint64_t pauseUntilUs_ = 0;
	uint32_t effectiveCap_ = 0;  // shrinks if the engine refuses to create more objects
};

}  // namespace opt
