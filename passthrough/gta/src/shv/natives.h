// GameApi implementation on top of ScriptHookV natives.
//
// Only the three primitives every SDK version exports (nativeInit / nativePush64 / nativeCall) are used, with
// verified hashes (native_hashes.h). That keeps this file independent of the SDK's namespace naming, which differs
// between SDK releases (GAMEPLAY vs MISC, WORLDPROBE vs SHAPETEST, ...).
#pragma once

#include <unordered_map>

#include "../core/game_api.h"

namespace shv {

class ShvGame final : public opt::GameApi {
public:
	uint64_t nowMicros() override;
	uint32_t joaat(const char* s) override;

	bool modelExists(uint32_t model) override;
	void requestModel(uint32_t model) override;
	bool modelLoaded(uint32_t model) override;
	void releaseModel(uint32_t model) override;
	opt::ModelDims modelDimensions(uint32_t model) override;

	opt::Handle createProp(uint32_t model, opt::Vec3 pos) override;
	void destroyProp(opt::Handle h) override;
	bool propExists(opt::Handle h) override;
	void moveProp(opt::Handle h, opt::Vec3 pos) override;
	void setPropActive(opt::Handle h, bool active) override;
	void setPropVariation(opt::Handle h, int variation) override;

	opt::PlayerSample samplePlayer() override;
	opt::RayHit castRay(opt::Vec3 from, opt::Vec3 to, opt::Handle ignore) override;
	float weaponDamage(uint32_t weapon) override;

	void notify(const char* text) override;
	void drawText(float x, float y, float scale, const char* text) override;
	bool onlineSession() override;

private:
	opt::ModelDims cachedDims(uint32_t model);
	std::unordered_map<uint32_t, opt::ModelDims> dimsCache_;
};

}  // namespace shv
