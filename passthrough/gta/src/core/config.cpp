#include "config.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <sstream>

namespace opt {

namespace {
std::string trim(const std::string& s) {
	size_t a = 0, b = s.size();
	while (a < b && std::isspace(static_cast<unsigned char>(s[a]))) ++a;
	while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) --b;
	return s.substr(a, b - a);
}
std::string lower(std::string s) {
	std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
	return s;
}
bool toBool(const std::string& v) {
	const std::string l = lower(v);
	return l == "1" || l == "true" || l == "yes" || l == "on";
}
long toLong(const std::string& v) { return std::strtol(v.c_str(), nullptr, 0); }  // base 0: accepts 0x78
double toDouble(const std::string& v) { return std::strtod(v.c_str(), nullptr); }
std::vector<std::string> splitCsv(const std::string& v) {
	std::vector<std::string> out;
	std::stringstream ss(v);
	std::string item;
	while (std::getline(ss, item, ',')) {
		item = trim(item);
		if (!item.empty()) out.push_back(item);
	}
	return out;
}
}  // namespace

bool parseConfigText(const std::string& text, Config& c, std::string* warnings) {
	std::istringstream in(text);
	std::string line, section;
	int lineNo = 0;
	auto warn = [&](const std::string& m) {
		if (warnings) *warnings += "line " + std::to_string(lineNo) + ": " + m + "\n";
	};
	while (std::getline(in, line)) {
		++lineNo;
		const size_t hash = line.find_first_of(";#");
		if (hash != std::string::npos) line.erase(hash);
		line = trim(line);
		if (line.empty()) continue;
		if (line.front() == '[' && line.back() == ']') {
			section = lower(trim(line.substr(1, line.size() - 2)));
			continue;
		}
		const size_t eq = line.find('=');
		if (eq == std::string::npos) {
			warn("expected key = value");
			continue;
		}
		const std::string rawKey = trim(line.substr(0, eq));
		const std::string key = lower(rawKey);
		const std::string val = trim(line.substr(eq + 1));

		if (section == "palette") {  // keys are registry names and must keep their case
			const auto parts = splitCsv(val);
			if (parts.empty()) {
				warn("palette entry needs a model name");
				continue;
			}
			c.paletteOverrides.push_back({rawKey, {parts[0], parts.size() > 1 ? static_cast<int>(toLong(parts[1])) : -1}});
			continue;
		}
		bool known = true;
		if (section == "bridge") {
			if (key == "name") c.shmName = val;
			else known = false;
		} else if (section == "render") {
			if (key == "model") c.model = val;
			else if (key == "model_candidates") c.modelCandidates = splitCsv(val);
			else if (key == "max_props") c.props.maxProps = static_cast<uint32_t>(toLong(val));
			else if (key == "spawn_radius") c.props.spawnRadius = static_cast<float>(toDouble(val));
			else if (key == "retire_margin") c.props.retireMargin = static_cast<float>(toDouble(val));
			else if (key == "max_spawns_per_frame") c.props.maxSpawnsPerFrame = static_cast<uint32_t>(toLong(val));
			else if (key == "max_retires_per_frame") c.props.maxRetiresPerFrame = static_cast<uint32_t>(toLong(val));
			else if (key == "frame_budget_us") c.props.frameBudgetUs = static_cast<uint32_t>(toLong(val));
			else if (key == "rescan_distance") c.props.rescanDistance = static_cast<float>(toDouble(val));
			else if (key == "parked_pool_max") c.props.parkedPoolMax = static_cast<uint32_t>(toLong(val));
			else if (key == "colour_buckets") c.colourBuckets = static_cast<int>(toLong(val));
			else known = false;
		} else if (section == "world") {
			if (key == "auto_anchor") c.autoAnchor = toBool(val);
			else if (key == "anchor_z_offset") c.anchorZOffset = static_cast<float>(toDouble(val));
			else if (key == "view_radius_h") c.viewRadiusH = static_cast<float>(toDouble(val));
			else if (key == "view_radius_v") c.viewRadiusV = static_cast<float>(toDouble(val));
			else if (key == "max_records_per_frame") c.maxRecordsPerFrame = static_cast<uint32_t>(toLong(val));
			else known = false;
		} else if (section == "interaction") {
			InteractionConfig& i = c.inter;
			if (key == "reach") i.reach = static_cast<float>(toDouble(val));
			else if (key == "tool_damage") i.toolDamage = static_cast<float>(toDouble(val));
			else if (key == "explosive_damage") i.explosiveDamage = static_cast<float>(toDouble(val));
			else if (key == "bullet_range") i.bulletRange = static_cast<float>(toDouble(val));
			else if (key == "shot_min_interval_ms") i.shotMinIntervalMs = static_cast<uint32_t>(toLong(val));
			else if (key == "collision_min_delta_v") i.collisionMinDeltaV = static_cast<float>(toDouble(val));
			else if (key == "ped_collision_min_delta_v") i.pedCollisionMinDeltaV = static_cast<float>(toDouble(val));
			else if (key == "collision_cooldown_ms") i.collisionCooldownMs = static_cast<uint32_t>(toLong(val));
			else if (key == "vehicle_mass_kg") i.vehicleMassKg = static_cast<float>(toDouble(val));
			else if (key == "shots") i.shots = toBool(val);
			else if (key == "collisions") i.collisions = toBool(val);
			else known = false;
		} else if (section == "keys") {
			if (key == "anchor") c.keyAnchor = static_cast<int>(toLong(val));
			else if (key == "overlay") c.keyOverlay = static_cast<int>(toLong(val));
			else if (key == "place") c.keyPlace = static_cast<int>(toLong(val));
			else if (key == "mine") c.keyMine = static_cast<int>(toLong(val));
			else if (key == "calibrate") c.keyCalibrate = static_cast<int>(toLong(val));
			else if (key == "prev_block") c.keyPrevBlock = static_cast<int>(toLong(val));
			else if (key == "next_block") c.keyNextBlock = static_cast<int>(toLong(val));
			else known = false;
		} else if (section == "ui") {
			if (key == "overlay") c.overlay = toBool(val);
			else known = false;
		} else {
			known = false;
		}
		if (!known) warn("unknown setting [" + section + "] " + rawKey);
	}
	return true;
}

bool loadConfigFile(const std::string& path, Config& out, std::string* warnings) {
	std::ifstream f(path, std::ios::binary);
	if (!f) return false;
	std::stringstream ss;
	ss << f.rdbuf();
	return parseConfigText(ss.str(), out, warnings);
}

}  // namespace opt
