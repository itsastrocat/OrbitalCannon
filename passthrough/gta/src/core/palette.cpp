#include "palette.h"

#include <algorithm>
#include <cstring>

namespace opt {

void Palette::clear() {
	entries_.clear();
	styles_.clear();
	placeable_.clear();
}

PropStyle Palette::resolve(const PaletteInfo& info) const {
	auto it = cfg_.byName.find(info.name);
	if (it != cfg_.byName.end()) return {it->second.model, it->second.variation, it->second.model != 0};
	PropStyle s;
	s.model = cfg_.defaultModel;
	s.render = s.model != 0;
	if (cfg_.colourBuckets > 0) {
		// Spread blocks over the model's texture variations by hue-ish mixing of the map colour. Stable per block.
		const uint32_t r = (info.rgb >> 16) & 255, g = (info.rgb >> 8) & 255, b = info.rgb & 255;
		s.variation = static_cast<int>(((r * 3u + g * 5u + b * 7u) / 3u) % static_cast<uint32_t>(cfg_.colourBuckets));
	}
	return s;
}

void Palette::setStyleConfig(StyleConfig cfg) {
	cfg_ = std::move(cfg);
	styles_.clear();
	for (const auto& kv : entries_) styles_[kv.first] = resolve(kv.second);
}

bool Palette::apply(const uint8_t* payload, uint32_t len) {
	if (len < sizeof(ocp::PaletteEntryHdr)) return false;
	ocp::PaletteEntryHdr h;
	std::memcpy(&h, payload, sizeof h);
	if (h.id == 0 || len < sizeof h + h.name_len) return false;
	PaletteInfo info;
	info.id = h.id;
	info.flags = h.flags;
	info.rgb = h.rgb;
	info.name.assign(reinterpret_cast<const char*>(payload + sizeof h), h.name_len);
	styles_[h.id] = resolve(info);
	const bool placeable = (h.flags & ocp::kPalPlaceable) != 0;
	entries_[h.id] = std::move(info);
	auto pos = std::lower_bound(placeable_.begin(), placeable_.end(), h.id);
	const bool present = pos != placeable_.end() && *pos == h.id;
	if (placeable && !present) placeable_.insert(pos, h.id);
	if (!placeable && present) placeable_.erase(pos);
	return true;
}

const PaletteInfo* Palette::find(uint16_t id) const {
	auto it = entries_.find(id);
	return it == entries_.end() ? nullptr : &it->second;
}

PropStyle Palette::styleFor(uint16_t id) const {
	auto it = styles_.find(id);
	if (it != styles_.end()) return it->second;
	PropStyle s;  // unannounced id (should not happen: the ring is ordered): fall back to the default model
	s.model = cfg_.defaultModel;
	s.render = s.model != 0;
	return s;
}

}  // namespace opt
