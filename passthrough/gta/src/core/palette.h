// GTA-side palette: the id -> block mapping announced by Minecraft, plus the policy that turns a block into a GTA
// prop model. GTA V has no per-block textures we can drive from script, so appearance is data-driven: the INI maps
// registry names (or colour buckets) onto whichever prop models the user wants.
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "../../../protocol/orbital_ipc.h"

namespace opt {

struct PropStyle {
	uint32_t model = 0;   // GTA model hash; 0 = nothing to render
	int variation = -1;   // texture variation / tint index, -1 = leave the model's default
	bool render = true;
};

class StyleSource {
public:
	virtual ~StyleSource() = default;
	virtual PropStyle styleFor(uint16_t paletteId) const = 0;
};

struct StyleConfig {
	uint32_t defaultModel = 0;
	int colourBuckets = 0;  // >0: derive a texture variation from the block's map colour
	struct Override {
		uint32_t model;
		int variation;
	};
	std::unordered_map<std::string, Override> byName;  // "minecraft:stone" -> model
};

struct PaletteInfo {
	uint16_t id = 0;
	uint16_t flags = 0;
	uint32_t rgb = 0;
	std::string name;
};

class Palette final : public StyleSource {
public:
	void clear();
	void setStyleConfig(StyleConfig cfg);  // re-resolves every known entry
	bool apply(const uint8_t* payload, uint32_t len);  // one PALETTE_ENTRY record
	const PaletteInfo* find(uint16_t id) const;
	const std::vector<uint16_t>& placeable() const { return placeable_; }
	size_t size() const { return entries_.size(); }

	PropStyle styleFor(uint16_t id) const override;

private:
	PropStyle resolve(const PaletteInfo& info) const;

	std::unordered_map<uint16_t, PaletteInfo> entries_;
	std::unordered_map<uint16_t, PropStyle> styles_;
	std::vector<uint16_t> placeable_;  // sorted ascending
	StyleConfig cfg_;
};

}  // namespace opt
