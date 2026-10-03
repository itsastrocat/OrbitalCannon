// The whole GTA-side mod as one object, independent of ScriptHookV: construct it with a GameApi and call frame()
// once per rendered frame. The ScriptHookV glue (shv/script.cpp) and the tests both drive exactly this class.
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "bridge.h"
#include "config.h"
#include "game_api.h"
#include "interaction.h"
#include "palette.h"
#include "prop_manager.h"
#include "transform.h"
#include "voxel_store.h"
#include "world_sync.h"

namespace opt {

// Picks the prop model closest to a 1 m cube by measuring candidates with GET_MODEL_DIMENSIONS at runtime.
class ModelCalibrator {
public:
	void start(const std::vector<std::string>& names);
	bool running() const { return running_; }
	void step(GameApi& game, uint64_t nowUs);
	bool found() const { return found_; }
	uint32_t best() const { return bestHash_; }
	const std::string& bestName() const { return bestName_; }
	double bestError() const { return bestErr_; }

private:
	std::vector<std::string> names_;
	size_t idx_ = 0;
	bool running_ = false, requested_ = false, found_ = false;
	uint32_t hash_ = 0, bestHash_ = 0;
	uint64_t requestedAtUs_ = 0;
	std::string bestName_;
	double bestErr_ = 1e9;
};

class App {
public:
	App(GameApi& game, Config cfg);
	~App();

	void frame();            // once per rendered frame, on the game thread
	void onKey(int vk);      // key-down
	void shutdown();         // idempotent; removes every prop

	// Introspection (tests and the overlay).
	Bridge& bridge() { return bridge_; }
	VoxelStore& store() { return store_; }
	Palette& palette() { return palette_; }
	PropManager& props() { return props_; }
	Interaction& interaction() { return inter_; }
	WorldSync& sync() { return sync_; }
	const Transform& transform() const { return tf_; }
	const ModelCalibrator& calibrator() const { return calib_; }
	const PlayerSample& lastSample() const { return sample_; }
	uint16_t selectedBlock() const { return selected_; }
	const std::string& lastAckText() const { return lastAckText_; }
	std::string statusLine() const;

private:
	void tryAttach(uint64_t nowUs);
	void applyModelPolicy();
	void teardownProps();
	void onSessionStarted(uint64_t nowUs);
	void anchorHere();
	void applyAnchor();
	void publishPlayer(const PlayerSample& p);
	void handleAcks();
	void drawOverlay();
	void cycleBlock(int dir);
	StyleConfig buildStyleConfig(uint32_t defaultModel);

	GameApi& game_;
	Config cfg_;
	Bridge bridge_;
	VoxelStore store_;
	Palette palette_;
	PropManager props_;
	WorldSync sync_;
	Transform tf_;
	Interaction inter_;
	ModelCalibrator calib_;

	PlayerSample sample_;
	Vec3 anchorGta_;
	bool haveAnchor_ = false;
	uint32_t anchorSerial_ = 0;
	uint64_t lastAttachTryUs_ = 0;
	uint64_t sessionStartUs_ = 0;
	bool wasAlive_ = false;
	bool needHello_ = false;
	bool inertNotified_ = false;
	bool overlay_ = true;
	bool shutDown_ = false;
	uint16_t selected_ = 0;
	std::string lastAckText_;
	uint64_t frameCount_ = 0;
	uint64_t lastDeniedNoticeUs_ = 0;
	double lastFrameCostUs_ = 0;
	std::string statusCache_;
	uint64_t statusCacheUs_ = 0;
};

}  // namespace opt
