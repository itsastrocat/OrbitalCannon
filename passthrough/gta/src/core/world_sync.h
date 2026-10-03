// Applies the Minecraft -> GTA record stream (ring A) to the local mirror: session resets, palette, voxel sections,
// incremental updates, acknowledgements and pings.
#pragma once

#include <cstdint>
#include <vector>

#include "bridge.h"
#include "common.h"
#include "palette.h"
#include "voxel_store.h"

namespace opt {

struct AckInfo {
	uint32_t seq;
	uint16_t result;
	int32_t x, y, z;
	float progress;
};

class WorldSync {
public:
	struct Session {
		bool active = false;
		uint64_t mcEpoch = 0;
		uint32_t serial = 0;
		Vec3 anchorMc;
		int32_t minY = -64, maxY = 320;
	};
	struct Counters {
		uint64_t sections = 0, updates = 0, palette = 0, acks = 0, malformed = 0, unknown = 0, unloads = 0;
	};

	WorldSync(VoxelStore& store, Palette& palette, Bridge& bridge) : store_(store), palette_(palette), bridge_(bridge) {}

	void onMessage(uint16_t type, const uint8_t* payload, uint32_t len, uint64_t nowUs);
	void reset();  // Minecraft vanished or restarted: forget its world

	const Session& session() const { return session_; }
	// True once after each SESSION_START; the app re-anchors and resets rendering when it sees it.
	bool takeSessionStarted() {
		const bool s = sessionStarted_;
		sessionStarted_ = false;
		return s;
	}
	std::vector<AckInfo>& acks() { return acks_; }
	const Counters& counters() const { return counters_; }

private:
	VoxelStore& store_;
	Palette& palette_;
	Bridge& bridge_;
	Session session_;
	bool sessionStarted_ = false;
	std::vector<AckInfo> acks_;
	Counters counters_;
};

}  // namespace opt
