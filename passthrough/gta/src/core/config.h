// INI configuration for the GTA-side mod (OrbitalPassthrough.ini next to the .asi).
#pragma once

#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "interaction.h"
#include "prop_manager.h"
#include "../../../protocol/orbital_ipc.h"

namespace opt {

struct Config {
	std::string shmName = ocp::kDefaultName;

	// ---- rendering ----
	std::string model = "auto";  // "auto" = measure the candidates at runtime and pick the closest to a 1 m cube
	std::vector<std::string> modelCandidates = {
		"prop_mb_sandblock_01", "prop_mb_sandblock_02", "prop_mb_sandblock_03", "prop_mb_sandblock_04",
		"prop_mb_sandblock_05", "prop_conc_blocks01a",  "prop_conc_blocks01b",  "prop_conc_blocks01c",
		"prop_box_wood01a",     "prop_box_wood02a",     "prop_box_wood04a",     "prop_box_wood05a",
		"prop_box_wood05b",     "prop_box_wood07a",     "prop_box_wood08a",     "prop_cons_crate",
		"prop_crate_01a",       "prop_crate_11e",       "prop_cs_cardbox_01",   "prop_boxpile_06b",
	};
	int colourBuckets = 0;
	PropConfig props;

	// ---- world ----
	bool autoAnchor = false;
	float anchorZOffset = 0.0f;  // lifts the whole Minecraft world above GTA's ground (avoids z-fighting with terrain)
	float viewRadiusH = 28.0f;
	float viewRadiusV = 24.0f;
	uint32_t maxRecordsPerFrame = 512;

	// ---- interaction ----
	InteractionConfig inter;

	// ---- keys (Windows virtual-key codes) ----
	int keyAnchor = 0x78;     // F9
	int keyOverlay = 0x79;    // F10
	int keyPlace = 0x75;      // F6
	int keyMine = 0x76;       // F7
	int keyCalibrate = 0x77;  // F8
	int keyPrevBlock = 0xDB;  // [
	int keyNextBlock = 0xDD;  // ]

	bool overlay = true;
	std::vector<std::pair<std::string, std::pair<std::string, int>>> paletteOverrides;  // name -> (model name, variation)
};

// Parses an INI file into `out` (fields not present keep their defaults). Returns false only if the file could not
// be read; unknown keys are reported through `warnings`.
bool loadConfigFile(const std::string& path, Config& out, std::string* warnings);
bool parseConfigText(const std::string& text, Config& out, std::string* warnings);

}  // namespace opt
