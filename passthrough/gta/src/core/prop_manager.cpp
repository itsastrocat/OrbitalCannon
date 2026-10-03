#include "prop_manager.h"

#include <algorithm>

namespace opt {

namespace {
struct MinHeapCmp {
	template <class C>
	bool operator()(const C& a, const C& b) const { return a.d2 > b.d2; }
};

inline float distSq(Vec3 pm, int32_t x, int32_t y, int32_t z) {
	const double dx = x + 0.5 - pm.x, dy = y + 0.5 - pm.y, dz = z + 0.5 - pm.z;
	return static_cast<float>(dx * dx + dy * dy + dz * dz);
}
inline float distSqKey(Vec3 pm, uint64_t key) {
	int32_t x, y, z;
	unpackVoxelKey(key, x, y, z);
	return distSq(pm, x, y, z);
}
}  // namespace

PropManager::PropManager(GameApi& game, VoxelStore& store, const StyleSource& styles, PropConfig cfg)
	: game_(game), store_(store), styles_(styles), cfg_(cfg), liveByKey_(cfg.maxProps * 2u),
	  slotByHandle_(cfg.maxProps * 2u), queued_(8192), effectiveCap_(cfg.maxProps) {
	slots_.reserve(cfg.maxProps);
}

Handle PropManager::handleAt(int32_t x, int32_t y, int32_t z) const {
	const uint32_t* s = liveByKey_.find(voxelKey(x, y, z));
	return s ? slots_[*s].handle : 0;
}

bool PropManager::voxelOfEntity(Handle h, int32_t& x, int32_t& y, int32_t& z) const {
	const uint32_t* s = slotByHandle_.find(static_cast<uint32_t>(h));
	if (!s) return false;
	unpackVoxelKey(slots_[*s].key, x, y, z);
	return true;
}

uint32_t PropManager::allocSlot() {
	if (!freeSlots_.empty()) {
		const uint32_t s = freeSlots_.back();
		freeSlots_.pop_back();
		return s;
	}
	slots_.emplace_back();
	return static_cast<uint32_t>(slots_.size() - 1);
}

PropManager::ModelInfo* PropManager::ensureModel(uint32_t model) {
	ModelInfo& m = models_[model];
	if (m.missing) return nullptr;
	if (m.loaded) return &m;
	if (!m.requested) {
		if (!game_.modelExists(model)) {
			m.missing = true;
			++stats_.modelsMissing;
			return nullptr;
		}
		game_.requestModel(model);
		m.requested = true;
	}
	if (m.checkedTick == tickSerial_) return nullptr;  // already asked this frame; the answer cannot change mid-frame
	if (!game_.modelLoaded(model)) {
		m.checkedTick = tickSerial_;
		return nullptr;
	}
	m.loaded = true;
	const ModelDims d = game_.modelDimensions(model);
	if (d.ok) m.centerOffset = {(d.min.x + d.max.x) * 0.5, (d.min.y + d.max.y) * 0.5, (d.min.z + d.max.z) * 0.5};
	return &m;
}

void PropManager::enqueue(uint64_t key, float d2, bool urgent) {
	if (urgent) {
		urgent_.push_back(key);
		queued_.put(key, 1);
		return;
	}
	if (!queued_.put(key, 1)) return;  // already waiting
	heap_.push_back({d2, key});
	std::push_heap(heap_.begin(), heap_.end(), MinHeapCmp{});
}

bool PropManager::popParked(uint32_t model, Handle& out) {
	auto it = parked_.find(model);
	if (it == parked_.end()) return false;
	std::vector<Handle>& v = it->second;
	while (!v.empty()) {
		out = v.back();
		v.pop_back();
		--parkedCount_;
		if (game_.propExists(out)) return true;  // a parked prop may have been removed by the engine; skip those
	}
	return false;
}

void PropManager::destroyOneParked() {
	for (auto& kv : parked_) {
		if (kv.second.empty()) continue;
		game_.destroyProp(kv.second.back());
		kv.second.pop_back();
		--parkedCount_;
		++stats_.destroyed;
		return;
	}
}

void PropManager::retire(uint32_t slot) {
	Slot& s = slots_[slot];
	if (!s.live) return;
	liveByKey_.erase(s.key);
	slotByHandle_.erase(static_cast<uint32_t>(s.handle));
	const Handle h = s.handle;
	const uint32_t model = s.model;
	s.live = false;
	s.handle = 0;
	freeSlots_.push_back(slot);
	if (parkedCount_ < cfg_.parkedPoolMax) {
		game_.setPropActive(h, false);
		parked_[model].push_back(h);
		++parkedCount_;
	} else {
		game_.destroyProp(h);
		++stats_.destroyed;
	}
}

bool PropManager::evictFarthest(float beyondD2) {
	while (!evictList_.empty()) {
		const EvictEntry e = evictList_.back();
		evictList_.pop_back();
		const Slot& s = slots_[e.slot];
		if (!s.live || s.key != e.key) continue;  // stale entry
		if (e.d2 <= beyondD2) {
			evictList_.clear();  // sorted ascending: everything left is nearer than the candidate
			return false;
		}
		retire(e.slot);
		++stats_.evicted;
		return true;
	}
	return false;
}

PropManager::SpawnResult PropManager::trySpawn(const Transform& tf, uint64_t key, float /*d2*/) {
	int32_t x, y, z;
	unpackVoxelKey(key, x, y, z);
	const uint16_t id = store_.get(x, y, z);
	if (id == 0 || liveByKey_.contains(key)) return SpawnResult::kDropped;
	const PropStyle style = styles_.styleFor(id);
	if (!style.render || style.model == 0) return SpawnResult::kDropped;
	ModelInfo* mi = ensureModel(style.model);
	if (!mi) return models_[style.model].missing ? SpawnResult::kDropped : SpawnResult::kDeferred;

	const Vec3 pos = tf.voxelCenterGta(x, y, z) - mi->centerOffset;
	Handle h = 0;
	if (popParked(style.model, h)) {
		game_.moveProp(h, pos);
		game_.setPropActive(h, true);
		++stats_.recycled;
	} else {
		if (liveByKey_.size() + parkedCount_ >= effectiveCap_) {
			if (parkedCount_ == 0) return SpawnResult::kNoCapacity;
			destroyOneParked();  // trade a parked prop of another model for a fresh one of ours
		}
		h = game_.createProp(style.model, pos);
		if (h == 0) {
			++stats_.createFailures;
			if (++consecutiveFailures_ >= 8) {
				// The engine is out of object slots. Shrink our cap to what we have and back off for a second.
				effectiveCap_ = std::max<uint32_t>(16, static_cast<uint32_t>(liveByKey_.size()) + parkedCount_);
				pauseUntilUs_ = game_.nowMicros() + 1'000'000;
				consecutiveFailures_ = 0;
			}
			return SpawnResult::kFailed;
		}
		consecutiveFailures_ = 0;
		++stats_.created;
	}
	if (style.variation >= 0) game_.setPropVariation(h, style.variation);

	const uint32_t slot = allocSlot();
	slots_[slot] = Slot{h, key, style.model, id, true};
	liveByKey_.put(key, slot);
	slotByHandle_.put(static_cast<uint32_t>(h), slot);
	return SpawnResult::kSpawned;
}

void PropManager::processChanges(Vec3 pm) {
	std::vector<VoxelChange>& changes = store_.changes();
	const float r2 = cfg_.spawnRadius * cfg_.spawnRadius;
	for (const VoxelChange& c : changes) {
		const uint64_t key = voxelKey(c.x, c.y, c.z);
		const uint32_t* sp = liveByKey_.find(key);
		if (c.newId == 0) {
			if (sp) retire(*sp);  // a broken block must vanish this frame, regardless of budgets
			continue;
		}
		if (sp) {
			if (slots_[*sp].id == c.newId) continue;
			retire(*sp);  // block was replaced by a different type: re-render with the right model
		}
		const float d2 = distSq(pm, c.x, c.y, c.z);
		if (c.urgent || d2 <= r2) enqueue(key, d2, c.urgent);
	}
	changes.clear();
}

void PropManager::maybeRescan(Vec3 pm, uint64_t nowUs) {
	const double dx = pm.x - lastScanPos_.x, dy = pm.y - lastScanPos_.y, dz = pm.z - lastScanPos_.z;
	const double rd = cfg_.rescanDistance;
	if (forceRescan_ || dx * dx + dy * dy + dz * dz >= rd * rd || nowUs - lastScanUs_ >= cfg_.rescanIntervalMs * 1000ull) {
		rescan(pm);
		lastScanUs_ = nowUs;
	}
}

void PropManager::rescan(Vec3 pm) {
	heap_.clear();
	queued_.clear();
	for (size_t i = urgentHead_; i < urgent_.size(); ++i) queued_.put(urgent_[i], 1);

	const double R = cfg_.spawnRadius;
	const double r2 = R * R;
	store_.forEachSection([&](const VoxelStore::Section& s) {
		if (s.count == 0) return;
		const double minx = s.sx * 16.0, miny = s.sy * 16.0, minz = s.sz * 16.0;
		const double ddx = std::max({minx - pm.x, 0.0, pm.x - (minx + 16.0)});
		const double ddy = std::max({miny - pm.y, 0.0, pm.y - (miny + 16.0)});
		const double ddz = std::max({minz - pm.z, 0.0, pm.z - (minz + 16.0)});
		if (ddx * ddx + ddy * ddy + ddz * ddz > r2) return;  // whole section out of range
		for (uint32_t i = 0; i < 4096; ++i) {
			if (s.ids[i] == 0) continue;
			const int32_t x = (s.sx << 4) | static_cast<int32_t>(i & 15);
			const int32_t z = (s.sz << 4) | static_cast<int32_t>((i >> 4) & 15);
			const int32_t y = (s.sy << 4) | static_cast<int32_t>(i >> 8);
			const float d2 = distSq(pm, x, y, z);
			if (d2 > r2) continue;
			const uint64_t key = voxelKey(x, y, z);
			if (liveByKey_.contains(key)) continue;
			if (queued_.put(key, 1)) heap_.push_back({d2, key});
		}
	});
	std::make_heap(heap_.begin(), heap_.end(), MinHeapCmp{});

	evictList_.clear();
	retireQueue_.clear();
	retireHead_ = 0;
	const double rr = R + cfg_.retireMargin;
	const double rr2 = rr * rr;
	for (uint32_t i = 0; i < slots_.size(); ++i) {
		const Slot& s = slots_[i];
		if (!s.live) continue;
		const float d2 = distSqKey(pm, s.key);
		if (d2 > rr2) retireQueue_.push_back(i);
		else evictList_.push_back({d2, i, s.key});
	}
	std::sort(evictList_.begin(), evictList_.end(), [](const EvictEntry& a, const EvictEntry& b) { return a.d2 < b.d2; });
	lastScanPos_ = pm;
	forceRescan_ = false;
}

void PropManager::processRetireQueue() {
	const double rr = cfg_.spawnRadius + cfg_.retireMargin;
	const double rr2 = rr * rr;
	uint32_t n = 0;
	while (retireHead_ < retireQueue_.size() && n < cfg_.maxRetiresPerFrame) {
		const uint32_t i = retireQueue_[retireHead_++];
		const Slot& s = slots_[i];
		if (!s.live) continue;
		if (distSqKey(curPm_, s.key) <= rr2) continue;  // the player came back; keep it
		retire(i);
		++n;
	}
}

void PropManager::spawnLoop(const Transform& tf, Vec3 pm, uint64_t startUs) {
	if (game_.nowMicros() < pauseUntilUs_) return;
	const float r2 = cfg_.spawnRadius * cfg_.spawnRadius;
	std::vector<Cand> deferred;
	uint32_t iter = 0;
	while (stats_.spawnedLastFrame < cfg_.maxSpawnsPerFrame) {
		if ((++iter & 7u) == 0 && game_.nowMicros() - startUs > cfg_.frameBudgetUs) break;

		uint64_t key;
		bool urgent = false;
		if (urgentHead_ < urgent_.size()) {
			key = urgent_[urgentHead_++];
			urgent = true;
		} else if (!heap_.empty()) {
			std::pop_heap(heap_.begin(), heap_.end(), MinHeapCmp{});
			key = heap_.back().key;
			heap_.pop_back();
		} else {
			break;
		}
		queued_.erase(key);
		const float d2 = distSqKey(pm, key);  // queued priorities go stale as the player moves; recompute
		if (!urgent && d2 > r2) continue;

		SpawnResult r = trySpawn(tf, key, d2);
		if (r == SpawnResult::kNoCapacity && evictFarthest(d2 + 4.0f)) r = trySpawn(tf, key, d2);

		if (r == SpawnResult::kSpawned) {
			++stats_.spawnedLastFrame;
		} else if (r == SpawnResult::kDeferred) {
			deferred.push_back({d2, key});
			if (deferred.size() >= 64) break;  // model still streaming in: do not burn the frame popping its voxels
		} else if (r == SpawnResult::kNoCapacity || r == SpawnResult::kFailed) {
			enqueue(key, d2, urgent);
			break;
		}
	}
	for (const Cand& c : deferred) enqueue(c.key, c.d2, false);
	if (urgentHead_ > 0 && urgentHead_ == urgent_.size()) {
		urgent_.clear();
		urgentHead_ = 0;
	}
}

void PropManager::validateSlice(const Transform&) {
	if (slots_.empty()) return;
	for (uint32_t n = 0; n < cfg_.validatePerFrame; ++n) {
		if (validateCursor_ >= slots_.size()) validateCursor_ = 0;
		const uint32_t i = validateCursor_++;
		Slot& s = slots_[i];
		if (!s.live || game_.propExists(s.handle)) continue;
		// The engine removed our prop (mission cleanup, streaming, a script reload...). Forget it and respawn promptly.
		const uint64_t key = s.key;
		liveByKey_.erase(key);
		slotByHandle_.erase(static_cast<uint32_t>(s.handle));
		s.live = false;
		s.handle = 0;
		freeSlots_.push_back(i);
		++stats_.respawned;
		enqueue(key, 0.0f, true);
	}
}

void PropManager::onTransformChanged() {
	for (uint32_t i = 0; i < slots_.size(); ++i) retire(i);
	heap_.clear();
	urgent_.clear();
	urgentHead_ = 0;
	queued_.clear();
	evictList_.clear();
	retireQueue_.clear();
	retireHead_ = 0;
	forceRescan_ = true;
}

void PropManager::tick(const Transform& tf, Vec3 playerGta) {
	const uint64_t t0 = game_.nowMicros();
	stats_.spawnedLastFrame = 0;
	++tickSerial_;
	curPm_ = tf.toMc(playerGta);
	processChanges(curPm_);
	maybeRescan(curPm_, t0);
	processRetireQueue();
	spawnLoop(tf, curPm_, t0);
	validateSlice(tf);
	stats_.live = static_cast<uint32_t>(liveByKey_.size());
	stats_.parked = parkedCount_;
	stats_.queued = static_cast<uint32_t>(heap_.size() + (urgent_.size() - urgentHead_));
}

void PropManager::shutdown() {
	for (Slot& s : slots_) {
		if (s.live && s.handle) game_.destroyProp(s.handle);
		s.live = false;
		s.handle = 0;
	}
	for (auto& kv : parked_) {
		for (Handle h : kv.second) game_.destroyProp(h);
		kv.second.clear();
	}
	parkedCount_ = 0;
	slots_.clear();
	freeSlots_.clear();
	liveByKey_.clear();
	slotByHandle_.clear();
	heap_.clear();
	urgent_.clear();
	urgentHead_ = 0;
	queued_.clear();
	evictList_.clear();
	retireQueue_.clear();
	retireHead_ = 0;
	for (auto& kv : models_) {
		if (kv.second.requested) game_.releaseModel(kv.first);
	}
	models_.clear();
	stats_.live = stats_.parked = stats_.queued = 0;
}

}  // namespace opt
