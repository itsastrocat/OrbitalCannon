#include "voxel_store.h"

#include <cstring>

namespace opt {

namespace {
inline uint32_t localIndex(int32_t x, int32_t y, int32_t z) {
	return static_cast<uint32_t>(x & 15) | (static_cast<uint32_t>(z & 15) << 4) | (static_cast<uint32_t>(y & 15) << 8);
}
}  // namespace

void VoxelStore::clear() {
	sections_.clear();
	changes_.clear();
	voxels_ = 0;
	cacheKey_ = ~0ull;
	cacheSection_ = nullptr;
}

VoxelStore::Section* VoxelStore::findSection(int32_t sx, int32_t sy, int32_t sz) const {
	const uint64_t k = sectionKey(sx, sy, sz);
	if (k == cacheKey_) return cacheSection_;
	auto it = sections_.find(k);
	Section* s = it == sections_.end() ? nullptr : it->second.get();
	cacheKey_ = k;
	cacheSection_ = s;
	return s;
}

VoxelStore::Section& VoxelStore::getOrCreate(int32_t sx, int32_t sy, int32_t sz) {
	if (Section* s = findSection(sx, sy, sz)) return *s;
	auto sec = std::make_unique<Section>();
	sec->sx = sx;
	sec->sy = sy;
	sec->sz = sz;
	Section* raw = sec.get();
	sections_.emplace(sectionKey(sx, sy, sz), std::move(sec));
	cacheKey_ = sectionKey(sx, sy, sz);
	cacheSection_ = raw;
	return *raw;
}

void VoxelStore::setInSection(Section& s, uint32_t local, uint16_t id, bool urgent) {
	const uint16_t old = s.ids[local];
	if (old == id) return;
	s.ids[local] = id;
	if (old == 0) {
		++s.count;
		++voxels_;
	} else if (id == 0) {
		--s.count;
		--voxels_;
	}
	changes_.push_back({(s.sx << 4) | static_cast<int32_t>(local & 15), (s.sy << 4) | static_cast<int32_t>(local >> 8),
	                    (s.sz << 4) | static_cast<int32_t>((local >> 4) & 15), old, id, urgent});
}

void VoxelStore::set(int32_t x, int32_t y, int32_t z, uint16_t id, bool urgent) {
	const int32_t sx = floorDiv16(x), sy = floorDiv16(y), sz = floorDiv16(z);
	if (id == 0) {
		Section* s = findSection(sx, sy, sz);
		if (!s) return;
		setInSection(*s, localIndex(x, y, z), 0, urgent);
		return;
	}
	setInSection(getOrCreate(sx, sy, sz), localIndex(x, y, z), id, urgent);
}

uint16_t VoxelStore::get(int32_t x, int32_t y, int32_t z) const {
	const Section* s = findSection(floorDiv16(x), floorDiv16(y), floorDiv16(z));
	return s ? s->ids[localIndex(x, y, z)] : 0;
}

bool VoxelStore::applySectionData(const uint8_t* payload, uint32_t len) {
	if (len < sizeof(ocp::SectionDataHdr)) return false;
	ocp::SectionDataHdr h;
	std::memcpy(&h, payload, sizeof h);
	if (len < sizeof h + static_cast<size_t>(h.count) * sizeof(ocp::VoxelEntry)) return false;

	Section& s = getOrCreate(h.sx, h.sy, h.sz);
	s.version = h.version;
	if (h.flags & ocp::kSecReplace) {
		for (uint32_t i = 0; i < 4096 && s.count > 0; ++i) {
			if (s.ids[i] != 0) setInSection(s, i, 0, false);
		}
	}
	const uint8_t* p = payload + sizeof h;
	for (uint32_t i = 0; i < h.count; ++i, p += sizeof(ocp::VoxelEntry)) {
		ocp::VoxelEntry e;
		std::memcpy(&e, p, sizeof e);
		if (e.local >= 4096 || e.id == 0) continue;  // ignore malformed entries rather than trusting the peer
		setInSection(s, e.local, e.id, false);
	}
	return true;
}

void VoxelStore::unloadSection(int32_t sx, int32_t sy, int32_t sz) {
	const uint64_t k = sectionKey(sx, sy, sz);
	auto it = sections_.find(k);
	if (it == sections_.end()) return;
	Section& s = *it->second;
	for (uint32_t i = 0; i < 4096 && s.count > 0; ++i) {
		if (s.ids[i] != 0) setInSection(s, i, 0, false);
	}
	sections_.erase(it);
	cacheKey_ = ~0ull;
	cacheSection_ = nullptr;
}

bool VoxelStore::applyVoxelUpdates(const uint8_t* payload, uint32_t len) {
	if (len < sizeof(ocp::VoxelUpdatesHdr)) return false;
	ocp::VoxelUpdatesHdr h;
	std::memcpy(&h, payload, sizeof h);
	if (len < sizeof h + static_cast<size_t>(h.count) * sizeof(ocp::VoxelUpdate)) return false;
	const uint8_t* p = payload + sizeof h;
	for (uint32_t i = 0; i < h.count; ++i, p += sizeof(ocp::VoxelUpdate)) {
		ocp::VoxelUpdate u;
		std::memcpy(&u, p, sizeof u);
		set(u.x, u.y, u.z, u.id, (u.flags & ocp::kUpdFromGta) != 0);
	}
	return true;
}

}  // namespace opt
