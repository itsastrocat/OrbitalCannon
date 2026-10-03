#include "world_sync.h"

#include <cstring>

#include "log.h"

namespace opt {

void WorldSync::reset() {
	store_.clear();
	palette_.clear();
	session_ = {};
	acks_.clear();
}

void WorldSync::onMessage(uint16_t type, const uint8_t* p, uint32_t len, uint64_t nowUs) {
	switch (type) {
		case ocp::kMsgSessionStart: {
			if (len < sizeof(ocp::SessionStart)) {
				++counters_.malformed;
				return;
			}
			ocp::SessionStart s;
			std::memcpy(&s, p, sizeof s);
			reset();
			session_.active = true;
			session_.mcEpoch = s.mc_epoch;
			session_.serial = s.sync_serial;
			session_.anchorMc = {s.anchor_x, s.anchor_y, s.anchor_z};
			session_.minY = s.min_y;
			session_.maxY = s.max_y;
			sessionStarted_ = true;
			logf("[sync] session start: serial %u, MC anchor (%.1f, %.1f, %.1f), build height %d..%d", s.sync_serial,
			     s.anchor_x, s.anchor_y, s.anchor_z, s.min_y, s.max_y);
			break;
		}
		case ocp::kMsgPaletteEntry:
			if (palette_.apply(p, len)) ++counters_.palette;
			else ++counters_.malformed;
			break;
		case ocp::kMsgSectionData:
			if (store_.applySectionData(p, len)) ++counters_.sections;
			else ++counters_.malformed;
			break;
		case ocp::kMsgSectionUnload: {
			if (len < sizeof(ocp::SectionUnload)) {
				++counters_.malformed;
				return;
			}
			ocp::SectionUnload u;
			std::memcpy(&u, p, sizeof u);
			store_.unloadSection(u.sx, u.sy, u.sz);
			++counters_.unloads;
			break;
		}
		case ocp::kMsgVoxelUpdates:
			if (store_.applyVoxelUpdates(p, len)) ++counters_.updates;
			else ++counters_.malformed;
			break;
		case ocp::kMsgAck: {
			if (len < sizeof(ocp::Ack)) {
				++counters_.malformed;
				return;
			}
			ocp::Ack a;
			std::memcpy(&a, p, sizeof a);
			acks_.push_back({a.request_seq, a.result, a.x, a.y, a.z, a.progress});
			++counters_.acks;
			break;
		}
		case ocp::kMsgPing: {
			if (len < sizeof(ocp::Ping)) return;
			ocp::Ping ping;
			std::memcpy(&ping, p, sizeof ping);
			bridge_.sendPong(ping.token);
			break;
		}
		case ocp::kMsgPong: {
			if (len < sizeof(ocp::Ping)) return;
			ocp::Ping pong;
			std::memcpy(&pong, p, sizeof pong);
			bridge_.onPong(pong.token, nowUs);
			break;
		}
		default:
			++counters_.unknown;  // forward compatibility: ignore record types from a newer Minecraft side
			break;
	}
}

}  // namespace opt
