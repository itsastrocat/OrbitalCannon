#include "app.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "log.h"

namespace opt {

// ---------------------------------------------------------------------------------------------------------------
// ModelCalibrator
// ---------------------------------------------------------------------------------------------------------------
void ModelCalibrator::start(const std::vector<std::string>& names) {
	names_ = names;
	idx_ = 0;
	running_ = !names.empty();
	requested_ = false;
	found_ = false;
	bestHash_ = 0;
	bestName_.clear();
	bestErr_ = 1e9;
}

void ModelCalibrator::step(GameApi& game, uint64_t nowUs) {
	if (!running_) return;
	if (idx_ >= names_.size()) {
		running_ = false;
		if (found_) logf("[calibrate] chose %s (worst axis error %.3f m)", bestName_.c_str(), bestErr_);
		else logf("[calibrate] none of the %llu candidate models exist in this game build", static_cast<unsigned long long>(names_.size()));
		return;
	}
	const std::string& name = names_[idx_];
	if (!requested_) {
		hash_ = game.joaat(name.c_str());
		if (!game.modelExists(hash_)) {
			logf("[calibrate] %-24s not in this game build", name.c_str());
			++idx_;
			return;
		}
		game.requestModel(hash_);
		requested_ = true;
		requestedAtUs_ = nowUs;
		return;
	}
	const bool loaded = game.modelLoaded(hash_);
	if (!loaded && nowUs - requestedAtUs_ < 1'500'000) return;  // still streaming; check next frame
	if (loaded) {
		const ModelDims d = game.modelDimensions(hash_);
		if (d.ok) {
			const double ex = std::fabs((d.max.x - d.min.x) - 1.0);
			const double ey = std::fabs((d.max.y - d.min.y) - 1.0);
			const double ez = std::fabs((d.max.z - d.min.z) - 1.0);
			const double err = std::max({ex, ey, ez});
			logf("[calibrate] %-24s size %.3f x %.3f x %.3f  error %.3f", name.c_str(), d.max.x - d.min.x, d.max.y - d.min.y,
			     d.max.z - d.min.z, err);
			if (err < bestErr_) {
				bestErr_ = err;
				bestHash_ = hash_;
				bestName_ = name;
				found_ = true;
			}
		}
	} else {
		logf("[calibrate] %-24s timed out while streaming", name.c_str());
	}
	game.releaseModel(hash_);
	requested_ = false;
	++idx_;
}

// ---------------------------------------------------------------------------------------------------------------
// App
// ---------------------------------------------------------------------------------------------------------------
App::App(GameApi& game, Config cfg)
	: game_(game), cfg_(std::move(cfg)), props_(game_, store_, palette_, cfg_.props), sync_(store_, palette_, bridge_),
	  inter_(game_, bridge_, props_, store_, tf_, cfg_.inter) {
	overlay_ = cfg_.overlay;
	applyModelPolicy();
}

App::~App() { shutdown(); }

StyleConfig App::buildStyleConfig(uint32_t defaultModel) {
	StyleConfig sc;
	sc.defaultModel = defaultModel;
	sc.colourBuckets = cfg_.colourBuckets;
	for (const auto& o : cfg_.paletteOverrides) {
		sc.byName[o.first] = {game_.joaat(o.second.first.c_str()), o.second.second};
	}
	return sc;
}

void App::applyModelPolicy() {
	if (cfg_.model != "auto" && !cfg_.model.empty()) {
		palette_.setStyleConfig(buildStyleConfig(game_.joaat(cfg_.model.c_str())));
		logf("[render] using configured model %s", cfg_.model.c_str());
	} else {
		palette_.setStyleConfig(buildStyleConfig(0));  // nothing renders until calibration picks a model
		calib_.start(cfg_.modelCandidates);
	}
}

void App::tryAttach(uint64_t nowUs) {
	lastAttachTryUs_ = nowUs;
	std::string err;
	if (!bridge_.attach(cfg_.shmName, &err)) {
		logf("[bridge] attach failed: %s", err.c_str());
		return;
	}
	needHello_ = true;
}

void App::teardownProps() {
	props_.shutdown();
	store_.clear();
}

void App::shutdown() {
	if (shutDown_) return;
	shutDown_ = true;
	props_.shutdown();
	bridge_.detach();
}

void App::cycleBlock(int dir) {
	const std::vector<uint16_t>& list = palette_.placeable();
	if (list.empty()) return;
	const size_t n = list.size();
	size_t i = 0;
	const auto it = std::find(list.begin(), list.end(), selected_);
	if (it != list.end()) {
		const size_t cur = static_cast<size_t>(it - list.begin());
		i = dir > 0 ? (cur + 1) % n : (cur + n - 1) % n;
	}
	selected_ = list[i];
	if (const PaletteInfo* info = palette_.find(selected_)) {
		const std::string msg = "Block: " + info->name;
		game_.notify(msg.c_str());
	}
}

void App::onKey(int vk) {
	if (shutDown_) return;
	if (vk == cfg_.keyOverlay) overlay_ = !overlay_;
	else if (vk == cfg_.keyAnchor) anchorHere();
	else if (vk == cfg_.keyMine) inter_.mine(sample_);
	else if (vk == cfg_.keyPlace) {
		if (selected_ == 0 && !palette_.placeable().empty()) selected_ = palette_.placeable().front();
		inter_.place(sample_, selected_);
	} else if (vk == cfg_.keyPrevBlock) cycleBlock(-1);
	else if (vk == cfg_.keyNextBlock) cycleBlock(+1);
	else if (vk == cfg_.keyCalibrate) {
		calib_.start(cfg_.modelCandidates);
		game_.notify("Measuring candidate models, see the log");
	}
}

void App::anchorHere() {
	if (!sync_.session().active || !sample_.valid) {
		game_.notify("Anchor failed: Minecraft is not connected yet");
		return;
	}
	anchorGta_ = {sample_.feet.x, sample_.feet.y, sample_.feet.z + cfg_.anchorZOffset};
	haveAnchor_ = true;
	applyAnchor();
	game_.notify("Minecraft world anchored here");
}

void App::applyAnchor() {
	if (!haveAnchor_ || !sync_.session().active) return;
	tf_.valid = true;
	tf_.anchorMc = sync_.session().anchorMc;
	tf_.anchorGta = anchorGta_;
	props_.onTransformChanged();
	bridge_.sendAnchor(anchorGta_.x, anchorGta_.y, anchorGta_.z, ++anchorSerial_);
	logf("[anchor] GTA (%.2f, %.2f, %.2f) <-> MC (%.2f, %.2f, %.2f)", anchorGta_.x, anchorGta_.y, anchorGta_.z,
	     tf_.anchorMc.x, tf_.anchorMc.y, tf_.anchorMc.z);
}

void App::onSessionStarted(uint64_t nowUs) {
	sessionStartUs_ = nowUs;
	tf_.valid = false;
	props_.onTransformChanged();
	selected_ = 0;
	// A reconnect keeps the world where the player pinned it: re-anchor automatically against the new session.
	if (haveAnchor_) applyAnchor();
}

void App::publishPlayer(const PlayerSample& p) {
	ocp::PlayerState s{};
	s.frame = frameCount_;
	s.px = p.feet.x;
	s.py = p.feet.y;
	s.pz = p.feet.z;
	s.vx = static_cast<float>(p.velocity.x);
	s.vy = static_cast<float>(p.velocity.y);
	s.vz = static_cast<float>(p.velocity.z);
	s.heading = p.heading;
	s.ex = p.camPos.x;
	s.ey = p.camPos.y;
	s.ez = p.camPos.z;
	s.ax = static_cast<float>(p.camDir.x);
	s.ay = static_cast<float>(p.camDir.y);
	s.az = static_cast<float>(p.camDir.z);
	for (int i = 0; i < 3; ++i) {
		s.hit_min[i] = p.hitMin[i];
		s.hit_max[i] = p.hitMax[i];
	}
	s.flags = p.flags | (tf_.valid ? ocp::kPfAnchored : 0u);
	s.weapon_hash = p.weapon;
	s.vehicle_model = p.vehicleModel;
	s.vehicle_speed = p.vehicleSpeed;
	bridge_.publishPlayer(s);
}

void App::handleAcks() {
	std::vector<AckInfo>& acks = sync_.acks();
	for (const AckInfo& a : acks) {
		static const char* kNames[] = {"broken",         "damaged",      "placed",       "denied: out of reach", "denied: not loaded",
		                               "denied: unbreakable", "denied: occupied", "denied: nothing there", "denied: you are in the way",
		                               "denied: slow down", "denied: build limit", "denied: not anchored", "denied: unknown block"};
		const char* name = a.result < sizeof(kNames) / sizeof(kNames[0]) ? kNames[a.result] : "?";
		char buf[96];
		std::snprintf(buf, sizeof buf, "#%u %s (%d,%d,%d) %.0f%%", a.seq, name, a.x, a.y, a.z, a.progress * 100.0f);
		lastAckText_ = buf;
		const uint64_t now = game_.nowMicros();
		if (a.result >= ocp::kAckDeniedReach && now - lastDeniedNoticeUs_ > 1'000'000) {
			lastDeniedNoticeUs_ = now;
			game_.notify(name);
		}
	}
	acks.clear();
}

std::string App::statusLine() const {
	char buf[256];
	const BridgeStats& b = bridge_.stats();
	const PropStats& p = props_.stats();
	std::snprintf(buf, sizeof buf, "OCP %s | rtt %.1f ms | props %u/%u q%u | sections %llu voxels %llu | mc tick %llu %.1f mspt | mod %.0f us/frame",
	              bridge_.mcAlive() ? (tf_.valid ? "LIVE" : "NEEDS ANCHOR") : "NO MC", b.rttMs, p.live, cfg_.props.maxProps,
	              p.queued, static_cast<unsigned long long>(store_.sectionCount()), static_cast<unsigned long long>(store_.voxelCount()),
	              static_cast<unsigned long long>(b.mcTick), b.mcMspt, lastFrameCostUs_);
	return buf;
}

void App::drawOverlay() {
	if (!overlay_) return;
	const uint64_t now = game_.nowMicros();
	if (now - statusCacheUs_ >= 250'000 || statusCache_.empty()) {  // text changes slowly: format at 4 Hz, draw every frame
		statusCache_ = statusLine();
		statusCacheUs_ = now;
	}
	game_.drawText(0.012f, 0.012f, 0.30f, statusCache_.c_str());
	if (calib_.running()) game_.drawText(0.012f, 0.034f, 0.30f, "Calibrating prop models...");
	else if (!calib_.found() && cfg_.model == "auto") game_.drawText(0.012f, 0.034f, 0.30f, "No usable cube model found: set [render] model=");
	if (!lastAckText_.empty()) game_.drawText(0.012f, 0.056f, 0.30f, lastAckText_.c_str());
	if (selected_ != 0) {
		if (const PaletteInfo* info = palette_.find(selected_)) {
			std::string s = "Selected: " + info->name;
			game_.drawText(0.012f, 0.078f, 0.30f, s.c_str());
		}
	}
}

void App::frame() {
	if (shutDown_) return;
	const uint64_t t0 = game_.nowMicros();
	++frameCount_;

	// GTA Online: stay completely inert. (ScriptHookV refuses to run there, and mods in Online risk a ban.)
	if (game_.onlineSession()) {
		if (!inertNotified_) {
			inertNotified_ = true;
			logf("[app] GTA Online detected: passthrough disabled");
			teardownProps();
			bridge_.detach();
		}
		return;
	}
	inertNotified_ = false;

	if (!bridge_.attached()) {
		if (t0 - lastAttachTryUs_ >= 2'000'000 || lastAttachTryUs_ == 0) tryAttach(t0);
		if (!bridge_.attached()) return;
	}
	if (calib_.running()) {
		calib_.step(game_, t0);
		if (!calib_.running() && calib_.found()) palette_.setStyleConfig(buildStyleConfig(calib_.best()));
	}

	bridge_.beginFrame(t0);
	if (bridge_.takePeerRestarted()) {
		sync_.reset();
		tf_.valid = false;
		props_.onTransformChanged();
		needHello_ = true;
	}
	const bool alive = bridge_.mcAlive();
	if (alive != wasAlive_) {
		wasAlive_ = alive;
		game_.notify(alive ? "Minecraft connected" : "Minecraft connection lost");
		if (alive) needHello_ = true;
	}
	if (alive && needHello_) {
		bridge_.sendHello(cfg_.viewRadiusH, cfg_.viewRadiusV, cfg_.props.maxProps);
		needHello_ = false;
	}

	if (alive) {
		bridge_.drainWorld([&](uint16_t type, const uint8_t* p, uint32_t len) { sync_.onMessage(type, p, len, t0); },
		                   cfg_.maxRecordsPerFrame);
		if (sync_.takeSessionStarted()) onSessionStarted(t0);
		handleAcks();
	}

	sample_ = game_.samplePlayer();
	if (sample_.valid) publishPlayer(sample_);

	if (cfg_.autoAnchor && alive && !tf_.valid && !haveAnchor_ && sync_.session().active && sample_.valid &&
	    (sample_.flags & ocp::kPfOnFoot) && t0 - sessionStartUs_ > 3'000'000) {
		anchorHere();
	}

	if (tf_.valid && sample_.valid) {
		props_.tick(tf_, sample_.feet);
		if (alive) inter_.frame(sample_, t0);
	}
	bridge_.flush();
	drawOverlay();
	lastFrameCostUs_ = static_cast<double>(game_.nowMicros() - t0);
}

}  // namespace opt
