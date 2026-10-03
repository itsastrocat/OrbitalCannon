// GTA-side end of the IPC channel: owns the mapping, the ring B writer and the ring A reader, runs the heartbeat
// and epoch handshake, and offers typed senders for every GTA -> Minecraft message.
#pragma once

#include <cstdint>
#include <string>

#include "shm.h"
#include "../../../protocol/orbital_ipc.h"

namespace opt {

struct BridgeStats {
	uint64_t recordsIn = 0, recordsOut = 0, droppedOut = 0, corruptIn = 0;
	double rttMs = 0, rttMaxMs = 0;
	uint64_t mcTick = 0;
	float mcMspt = 0;
	bool mcAlive = false;
};

class Bridge {
public:
	bool attach(const std::string& name, std::string* error);
	void detach();
	bool attached() const { return map_.isOpen(); }

	// Per-frame housekeeping: bumps our heartbeat, tracks the peer's, detects a Minecraft restart, sends pings.
	void beginFrame(uint64_t nowUs);
	bool mcAlive() const { return mcAlive_; }
	// True exactly once after Minecraft's epoch changed (it restarted): drop all world state and re-HELLO.
	bool takePeerRestarted() {
		const bool r = peerRestarted_;
		peerRestarted_ = false;
		return r;
	}

	// Calls handler(type, payload, len) for up to maxRecords records from ring A.
	template <class F>
	uint32_t drainWorld(F&& handler, uint32_t maxRecords) {
		const uint32_t n = readerA_.drain(handler, maxRecords);
		stats_.recordsIn += n;
		return n;
	}
	uint64_t pendingWorldBytes() const { return readerA_.available(); }

	bool publishPlayer(const ocp::PlayerState& s);
	bool readMcState(ocp::McState& out) const;

	// ---- GTA -> Minecraft events (ring B). All return false when the ring is full (the event is dropped). ----
	bool sendHello(float viewH, float viewV, uint32_t propBudget);
	bool sendAnchor(double gx, double gy, double gz, uint32_t serial);
	uint32_t sendBreak(uint8_t source, uint8_t face, int32_t x, int32_t y, int32_t z, float damage, uint32_t weapon);
	uint32_t sendPlace(uint8_t face, uint16_t paletteId, int32_t x, int32_t y, int32_t z);
	bool sendCollision(const ocp::CollisionEvent& e);
	bool sendPong(uint64_t token);
	bool sendPing(uint64_t token);
	// Publishes everything queued this frame and wakes the Java reader.
	void flush();

	void onPong(uint64_t token, uint64_t nowUs);
	const BridgeStats& stats() const { return stats_; }
	uint64_t gtaEpoch() const { return gtaEpoch_; }
	uint64_t mcEpoch() const { return mcEpochSeen_; }

private:
	template <class T>
	bool write(uint16_t type, const T& v) {
		if (!writerB_.valid()) return false;
		uint8_t* p = writerB_.begin(type, sizeof(T));
		if (!p) {
			++stats_.droppedOut;
			return false;
		}
		std::memcpy(p, &v, sizeof(T));
		++stats_.recordsOut;
		dirty_ = true;
		return true;
	}

	ocp::SharedMapping map_;
	ocp::Layout layout_;
	ocp::RingReader readerA_;
	ocp::RingWriter writerB_;
	uint64_t gtaEpoch_ = 0;
	uint64_t mcEpochSeen_ = 0;
	uint64_t lastMcHeartbeat_ = 0;
	uint64_t lastMcBeatUs_ = 0;
	uint64_t lastPingUs_ = 0;
	uint32_t nextSeq_ = 1;
	bool mcAlive_ = false;
	bool peerRestarted_ = false;
	bool dirty_ = false;
	BridgeStats stats_;
};

}  // namespace opt
