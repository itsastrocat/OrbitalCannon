#include "bridge.h"

#include <chrono>
#include <random>

#include "log.h"

namespace opt {

namespace {
uint64_t randomEpoch() {
	std::random_device rd;
	uint64_t e = (static_cast<uint64_t>(rd()) << 32) ^ rd() ^
	             static_cast<uint64_t>(std::chrono::steady_clock::now().time_since_epoch().count());
	return e ? e : 1;
}
constexpr uint64_t kMcTimeoutUs = 2'000'000;  // Minecraft is considered gone after 2 s without a heartbeat
constexpr uint64_t kPingPeriodUs = 1'000'000;
}  // namespace

bool Bridge::attach(const std::string& name, std::string* error) {
	detach();
	if (!map_.open(name, error)) return false;
	layout_ = ocp::Layout::over(map_.data());
	const ocp::AttachResult r = ocp::init_or_validate(layout_);
	if (r != ocp::AttachResult::kOk) {
		if (error) *error = "shared memory header rejected (code " + std::to_string(static_cast<int>(r)) + "): wrong protocol version or foreign data";
		map_.close();
		return false;
	}
	gtaEpoch_ = randomEpoch();
	ocp::store_release(layout_.hdr->gta_heartbeat, 0);
	ocp::store_release(layout_.hdr->gta_epoch, gtaEpoch_);  // published last: the peer resyncs when it sees a new epoch

	readerA_ = ocp::RingReader(layout_.ring_a_ctl, layout_.ring_a);
	readerA_.skip_all();  // anything already in ring A belongs to a session we were not part of
	writerB_ = ocp::RingWriter(layout_.ring_b_ctl, layout_.ring_b);

	mcEpochSeen_ = ocp::load_acquire(layout_.hdr->mc_epoch);
	lastMcHeartbeat_ = ocp::load_acquire(layout_.hdr->mc_heartbeat);
	lastMcBeatUs_ = 0;
	mcAlive_ = false;
	peerRestarted_ = false;
	stats_ = {};
	logf("[bridge] attached, epoch %016llx, ring A %llu KiB, ring B %llu KiB", static_cast<unsigned long long>(gtaEpoch_),
	     static_cast<unsigned long long>(ocp::kRingACap >> 10), static_cast<unsigned long long>(ocp::kRingBCap >> 10));
	return true;
}

void Bridge::detach() {
	if (map_.isOpen()) {
		ocp::store_release(layout_.hdr->gta_epoch, 0);  // tells Minecraft we are gone
	}
	map_.close();
	layout_ = {};
	readerA_ = {};
	writerB_ = {};
	mcAlive_ = false;
}

void Bridge::beginFrame(uint64_t nowUs) {
	if (!attached()) return;
	ocp::fetch_add_relaxed(layout_.hdr->gta_heartbeat, 1);  // single writer; a relaxed add is enough for a liveness counter

	const uint64_t epoch = ocp::load_acquire(layout_.hdr->mc_epoch);
	if (epoch != mcEpochSeen_) {
		mcEpochSeen_ = epoch;
		peerRestarted_ = epoch != 0;
		logf("[bridge] Minecraft epoch changed (%016llx): resynchronising", static_cast<unsigned long long>(epoch));
	}
	const uint64_t hb = ocp::load_acquire(layout_.hdr->mc_heartbeat);
	if (hb != lastMcHeartbeat_) {
		lastMcHeartbeat_ = hb;
		lastMcBeatUs_ = nowUs;
	}
	const bool alive = lastMcBeatUs_ != 0 && nowUs - lastMcBeatUs_ < kMcTimeoutUs && mcEpochSeen_ != 0;
	if (alive != mcAlive_) {
		mcAlive_ = alive;
		logf("[bridge] Minecraft %s", alive ? "connected" : "lost (heartbeat timeout)");
	}
	stats_.mcAlive = mcAlive_;
	stats_.corruptIn = readerA_.corrupt_count();
	stats_.droppedOut = ocp::load_relaxed(layout_.ring_b_ctl->dropped);

	if (mcAlive_ && nowUs - lastPingUs_ >= kPingPeriodUs) {
		lastPingUs_ = nowUs;
		ocp::Ping p{nowUs};
		write(ocp::kMsgPing, p);
	}
	ocp::McState ms;
	if (ocp::seq_read(*layout_.mc_state, ms)) {
		stats_.mcTick = ms.tick;
		stats_.mcMspt = ms.mspt;
	}
}

bool Bridge::publishPlayer(const ocp::PlayerState& s) {
	if (!attached()) return false;
	ocp::seq_write(*layout_.gta_state, s);
	return true;
}

bool Bridge::readMcState(ocp::McState& out) const {
	return attached() && ocp::seq_read(*layout_.mc_state, out);
}

bool Bridge::sendHello(float viewH, float viewV, uint32_t propBudget) {
	ocp::Hello h{};
	h.gta_epoch = gtaEpoch_;
	h.version = ocp::kVersion;
	h.view_radius_h = viewH;
	h.view_radius_v = viewV;
	h.prop_budget = propBudget;
	return write(ocp::kMsgHello, h);
}

bool Bridge::sendAnchor(double gx, double gy, double gz, uint32_t serial) {
	ocp::Anchor a{};
	a.gx = gx;
	a.gy = gy;
	a.gz = gz;
	a.serial = serial;
	return write(ocp::kMsgAnchor, a);
}

uint32_t Bridge::sendBreak(uint8_t source, uint8_t face, int32_t x, int32_t y, int32_t z, float damage, uint32_t weapon) {
	ocp::BreakRequest b{};
	b.seq = nextSeq_;
	b.source = source;
	b.face = face;
	b.x = x;
	b.y = y;
	b.z = z;
	b.damage = damage;
	b.weapon_hash = weapon;
	return write(ocp::kMsgBreakRequest, b) ? nextSeq_++ : 0;
}

uint32_t Bridge::sendPlace(uint8_t face, uint16_t paletteId, int32_t x, int32_t y, int32_t z) {
	ocp::PlaceRequest p{};
	p.seq = nextSeq_;
	p.face = face;
	p.palette_id = paletteId;
	p.x = x;
	p.y = y;
	p.z = z;
	return write(ocp::kMsgPlaceRequest, p) ? nextSeq_++ : 0;
}

bool Bridge::sendCollision(const ocp::CollisionEvent& e) { return write(ocp::kMsgCollision, e); }

bool Bridge::sendPing(uint64_t token) {
	ocp::Ping p{token};
	return write(ocp::kMsgPing, p);
}

bool Bridge::sendPong(uint64_t token) {
	ocp::Ping p{token};
	return write(ocp::kMsgPong, p);
}

void Bridge::flush() {
	if (!dirty_ || !attached()) return;
	writerB_.commit();
	map_.signalPeer();
	dirty_ = false;
}

void Bridge::onPong(uint64_t token, uint64_t nowUs) {
	if (token == 0 || nowUs < token) return;
	stats_.rttMs = static_cast<double>(nowUs - token) / 1000.0;
	if (stats_.rttMs > stats_.rttMaxMs) stats_.rttMaxMs = stats_.rttMs;
}

}  // namespace opt
