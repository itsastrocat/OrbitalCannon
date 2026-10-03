// Verifies the ScriptHookV layer against a scripted fake of the game's native dispatcher:
//   * every native hash the mod calls is in the table generated from the FiveM reference, with the right argument count
//   * argument encoding (floats, bools, pointers, padded Vector3 slots) and result decoding are correct
//   * the Euler-angle -> direction and heading -> hitbox maths are right
#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "test_util.h"
#include "../../protocol/orbital_ipc.h"
#include "../src/core/hash.h"
#include "../src/shv/native_hashes.h"
#include "../src/shv/native_call.h"
#include "../src/shv/natives.h"

namespace {
struct Sig {
	const char* name;
	uint64_t hash;
	int params;
	const char* result;
};
const Sig kSigs[] = {
#include "native_signatures.inc"
};

struct Rec {
	uint64_t hash;
	std::vector<uint64_t> args;
};
std::vector<Rec> g_calls;
uint64_t g_ret[4];
using Handler = std::function<void(const Rec&, uint64_t* ret)>;
Handler g_handler;

float f32(uint64_t slot) {
	float f;
	uint32_t lo = static_cast<uint32_t>(slot);
	std::memcpy(&f, &lo, 4);
	return f;
}
void setVec(uint64_t* ret, float x, float y, float z) {
	auto put = [&](int i, float v) {
		uint32_t b;
		std::memcpy(&b, &v, 4);
		ret[i] = b;
	};
	put(0, x);
	put(1, y);
	put(2, z);
}
void writeVecTo(uint64_t ptr, float x, float y, float z) {
	auto* v = reinterpret_cast<shv::ScriptVec3*>(ptr);
	v->x = x;
	v->y = y;
	v->z = z;
}
}  // namespace

// The stub SDK entry points.
void scriptWait(DWORD) {}
void scriptRegister(HMODULE, void (*)()) {}
void scriptUnregister(HMODULE) {}
void keyboardHandlerRegister(KeyboardHandler) {}
void keyboardHandlerUnregister(KeyboardHandler) {}
void nativeInit(UINT64 hash) { g_calls.push_back({hash, {}}); }
void nativePush64(UINT64 v) { g_calls.back().args.push_back(v); }
PUINT64 nativeCall() {
	std::memset(g_ret, 0, sizeof g_ret);
	if (g_handler) g_handler(g_calls.back(), g_ret);
	return g_ret;
}

namespace {
using namespace shv::nat;

// A tiny GTA: answers natives from a configurable scene.
struct Scene {
	int32_t ped = 77, vehicle = 0;
	bool inVehicle = false, shooting = false, hasImpact = false, ragdoll = false, dead = false, online = false;
	float cx = 100, cy = 200, cz = 31, vx = 1, vy = 2, vz = 3, heading = 0;
	float camx = 10, camy = 20, camz = 30, camPitch = 0, camYaw = 0;
	float impX = 5, impY = 6, impZ = 7;
	uint32_t model = 0x1234;
	std::map<uint32_t, std::array<float, 6>> dims;  // model -> min xyz, max xyz
	int32_t nextObject = 4242;
	bool rayHit = true;
	float rayEnd[3] = {8, 9, 10}, rayNormal[3] = {0, 0, 1};
	int32_t rayEntity = 0x4444;
	std::string lastNotify;

	Handler handler() {
		return [this](const Rec& c, uint64_t* ret) {
			switch (c.hash) {
				case PLAYER_PED_ID: ret[0] = static_cast<uint32_t>(ped); break;
				case IS_ENTITY_DEAD: ret[0] = dead; break;
				case IS_PED_IN_ANY_VEHICLE: ret[0] = inVehicle; break;
				case GET_VEHICLE_PED_IS_IN: ret[0] = static_cast<uint32_t>(vehicle); break;
				case GET_ENTITY_COORDS: setVec(ret, cx, cy, cz); break;
				case GET_ENTITY_VELOCITY: setVec(ret, vx, vy, vz); break;
				case GET_ENTITY_HEADING: { uint32_t b; std::memcpy(&b, &heading, 4); ret[0] = b; break; }
				case GET_ENTITY_MODEL: ret[0] = model; break;
				case GET_MODEL_DIMENSIONS: {
					auto it = dims.find(static_cast<uint32_t>(c.args[0]));
					if (it != dims.end()) {
						writeVecTo(c.args[1], it->second[0], it->second[1], it->second[2]);
						writeVecTo(c.args[2], it->second[3], it->second[4], it->second[5]);
					}
					break;
				}
				case GET_GAMEPLAY_CAM_COORD: setVec(ret, camx, camy, camz); break;
				case GET_GAMEPLAY_CAM_ROT: setVec(ret, camPitch, 0, camYaw); break;
				case GET_SELECTED_PED_WEAPON: ret[0] = 0xAAAA; break;
				case IS_PED_SHOOTING: ret[0] = shooting; break;
				case GET_PED_LAST_WEAPON_IMPACT_COORD:
					if (hasImpact) {
						writeVecTo(c.args[1], impX, impY, impZ);
						ret[0] = 1;
					}
					break;
				case IS_PED_ON_FOOT: ret[0] = !inVehicle; break;
				case IS_PED_RAGDOLL: ret[0] = ragdoll; break;
				case GET_ENTITY_SPEED: { float s = 12.5f; uint32_t b; std::memcpy(&b, &s, 4); ret[0] = b; break; }
				case GET_WEAPON_DAMAGE: { float s = 33.0f; uint32_t b; std::memcpy(&b, &s, 4); ret[0] = b; break; }
				case NETWORK_IS_GAME_IN_PROGRESS: ret[0] = online; break;
				case IS_MODEL_IN_CDIMAGE: case IS_MODEL_VALID: case HAS_MODEL_LOADED: case DOES_ENTITY_EXIST: ret[0] = 1; break;
				case CREATE_OBJECT_NO_OFFSET: ret[0] = static_cast<uint32_t>(nextObject); break;
				case START_EXPENSIVE_SYNCHRONOUS_SHAPE_TEST_LOS_PROBE: ret[0] = 9; break;
				case GET_SHAPE_TEST_RESULT:
					*reinterpret_cast<int32_t*>(c.args[1]) = rayHit;
					writeVecTo(c.args[2], rayEnd[0], rayEnd[1], rayEnd[2]);
					writeVecTo(c.args[3], rayNormal[0], rayNormal[1], rayNormal[2]);
					*reinterpret_cast<int32_t*>(c.args[4]) = rayEntity;
					ret[0] = 2;
					break;
				case ADD_TEXT_COMPONENT_SUBSTRING_PLAYER_NAME: lastNotify = reinterpret_cast<const char*>(c.args[0]); break;
				default: break;
			}
		};
	}
};

const Sig* sigFor(uint64_t h) {
	for (const Sig& s : kSigs)
		if (s.hash == h) return &s;
	return nullptr;
}
}  // namespace

TEST(natives_every_call_matches_the_reference_signature_table) {
	g_calls.clear();
	Scene sc;
	sc.dims[0x1234] = {-0.35f, -0.35f, -1.0f, 0.35f, 0.35f, 0.8f};
	g_handler = sc.handler();
	shv::ShvGame g;
	// Exercise the entire GameApi surface, on foot and in a vehicle while shooting.
	g.joaat("x");
	g.modelExists(1);
	g.requestModel(1);
	g.modelLoaded(1);
	g.modelDimensions(0x1234);
	g.releaseModel(1);
	const opt::Handle h = g.createProp(1, {1, 2, 3});
	g.moveProp(h, {4, 5, 6});
	g.setPropActive(h, false);
	g.setPropVariation(h, 3);
	g.propExists(h);
	g.destroyProp(h);
	g.samplePlayer();
	sc.inVehicle = true;
	sc.vehicle = 55;
	sc.shooting = sc.hasImpact = true;
	g.samplePlayer();
	g.castRay({0, 0, 0}, {1, 1, 1}, 7);
	g.weaponDamage(1);
	g.notify("hello");
	g.drawText(0.1f, 0.2f, 0.3f, "hud");
	g.onlineSession();
	g.nowMicros();

	std::set<uint64_t> used;
	for (const Rec& c : g_calls) {
		const Sig* s = sigFor(c.hash);
		CHECK(s != nullptr);
		if (!s) {
			std::printf("    unknown native hash 0x%016llX\n", static_cast<unsigned long long>(c.hash));
			continue;
		}
		used.insert(c.hash);
		if (static_cast<int>(c.args.size()) != s->params) {
			std::printf("    %s called with %zu args, reference says %d\n", s->name, c.args.size(), s->params);
			CHECK(false);
		}
	}
	for (const Sig& s : kSigs) {
		if (!used.count(s.hash)) std::printf("    native %s is declared but never exercised\n", s.name);
		CHECK(used.count(s.hash) == 1);
	}
	CHECK(kSigs[0].hash != 0);
	g_handler = nullptr;
}

TEST(natives_encode_arguments_exactly_as_scripthook_expects) {
	g_calls.clear();
	Scene sc;
	g_handler = sc.handler();
	shv::ShvGame g;
	const opt::Handle h = g.createProp(0xDEADBEEF, {1000.25, 2000.5, 30.75});
	CHECK_EQ(h, 4242);
	const Rec& create = g_calls[0];
	CHECK_EQ(create.hash, CREATE_OBJECT_NO_OFFSET);
	CHECK_EQ(create.args.size(), 7u);
	CHECK_EQ(create.args[0], 0xDEADBEEFull);  // 32-bit hash zero-extended, not sign-extended
	CHECK_NEAR(f32(create.args[1]), 1000.25, 1e-6);
	CHECK_NEAR(f32(create.args[2]), 2000.5, 1e-6);
	CHECK_NEAR(f32(create.args[3]), 30.75, 1e-6);
	CHECK_EQ(create.args[4] | create.args[5] | create.args[6], 0ull);
	// Follow-up configuration: freeze + collision + load-collision flag + invincible, with 1/0 booleans.
	bool froze = false, collided = false;
	for (const Rec& c : g_calls) {
		if (c.hash == FREEZE_ENTITY_POSITION) froze = c.args[0] == 4242 && c.args[1] == 1;
		if (c.hash == SET_ENTITY_COLLISION) collided = c.args[0] == 4242 && c.args[1] == 1 && c.args[2] == 0;
	}
	CHECK(froze && collided);

	g_calls.clear();
	int32_t seen = -1;
	g_handler = [&](const Rec& c, uint64_t*) {
		if (c.hash == DELETE_OBJECT) seen = *reinterpret_cast<int32_t*>(c.args[0]);  // pointer to the handle
	};
	g.destroyProp(777);
	CHECK_EQ(seen, 777);

	g_calls.clear();
	g_handler = sc.handler();
	g.setPropActive(55, false);
	CHECK_EQ(g_calls.size(), 2u);
	CHECK(g_calls[0].hash == SET_ENTITY_VISIBLE && g_calls[0].args[1] == 0);
	CHECK(g_calls[1].hash == SET_ENTITY_COLLISION && g_calls[1].args[1] == 0);

	g_calls.clear();
	g.setPropVariation(55, 9);
	CHECK(g_calls[0].hash == SET_OBJECT_TEXTURE_VARIATION && g_calls[0].args[0] == 55 && g_calls[0].args[1] == 9);
	g_handler = nullptr;
}

TEST(natives_decode_player_state_camera_and_hitbox) {
	Scene sc;
	sc.dims[0x1234] = {-0.35f, -0.35f, -1.0f, 0.35f, 0.35f, 0.8f};
	g_handler = sc.handler();
	shv::ShvGame g;
	opt::PlayerSample p = g.samplePlayer();
	CHECK(p.valid);
	CHECK_NEAR(p.feet.x, 100, 1e-5);
	CHECK_NEAR(p.feet.z, 30.0, 1e-5);  // origin 31, model bottom at -1 -> feet at 30
	CHECK_NEAR(p.velocity.y, 2, 1e-5);
	CHECK_NEAR(p.camPos.y, 20, 1e-5);
	CHECK_NEAR(p.camDir.y, 1.0, 1e-9);  // yaw 0, pitch 0 looks along +Y (north)
	CHECK_NEAR(p.hitMax[2], 1.8, 1e-5);
	CHECK(p.flags & ocp::kPfOnFoot);
	CHECK(!p.shooting && !p.hasImpact);
	CHECK_EQ(p.weapon, 0xAAAAu);

	sc.camYaw = 90;  // GTA yaw rotates counter-clockwise: 90 degrees faces west (-X)
	p = g.samplePlayer();
	CHECK_NEAR(p.camDir.x, -1.0, 1e-9);
	CHECK_NEAR(p.camDir.y, 0.0, 1e-9);
	sc.camYaw = 0;
	sc.camPitch = -45;
	p = g.samplePlayer();
	CHECK_NEAR(p.camDir.z, -std::sqrt(0.5), 1e-9);
	CHECK_NEAR(length(opt::Vec3{p.camDir.x, p.camDir.y, p.camDir.z}), 1.0, 1e-9);

	// A long car rotated 90 degrees swaps its X/Y extents in the world-axis hitbox.
	sc.inVehicle = true;
	sc.vehicle = 55;
	sc.model = 0xCAFE;
	sc.heading = 90;
	sc.dims[0xCAFE] = {-1.0f, -2.5f, -0.5f, 1.0f, 2.5f, 0.7f};
	sc.shooting = sc.hasImpact = true;
	p = g.samplePlayer();
	CHECK_EQ(p.vehicle, 55);
	CHECK_NEAR(p.hitMin[0], -2.5, 1e-4);
	CHECK_NEAR(p.hitMax[0], 2.5, 1e-4);
	CHECK_NEAR(p.hitMin[1], -1.0, 1e-4);
	CHECK_NEAR(p.hitMax[1], 1.0, 1e-4);
	CHECK_NEAR(p.hitMax[2], 1.2, 1e-4);
	CHECK_NEAR(p.feet.z, 31 - 0.5, 1e-5);
	CHECK(p.shooting && p.hasImpact);
	CHECK_NEAR(p.impact.x, 5, 1e-5);
	CHECK_NEAR(p.impact.z, 7, 1e-5);
	CHECK_NEAR(p.vehicleSpeed, 12.5, 1e-5);
	CHECK_EQ(p.vehicleModel, 0xCAFEu);
	CHECK(p.flags & ocp::kPfInVehicle);

	sc.dead = true;
	CHECK(!g.samplePlayer().valid);
	sc.ped = 0;
	CHECK(!g.samplePlayer().valid);
	g_handler = nullptr;
}

TEST(natives_raycast_round_trips_hit_data_and_pointer_outparams) {
	Scene sc;
	g_handler = sc.handler();
	shv::ShvGame g;
	g_calls.clear();
	opt::RayHit r = g.castRay({1.5, 2.5, 3.5}, {4.5, 5.5, 6.5}, 9);
	CHECK(r.hit);
	CHECK_NEAR(r.point.x, 8, 1e-6);
	CHECK_NEAR(r.normal.z, 1, 1e-6);
	CHECK_EQ(r.entity, 0x4444);
	const Rec& start = g_calls[0];
	CHECK_EQ(start.hash, START_EXPENSIVE_SYNCHRONOUS_SHAPE_TEST_LOS_PROBE);
	CHECK_NEAR(f32(start.args[0]), 1.5, 1e-6);
	CHECK_NEAR(f32(start.args[5]), 6.5, 1e-6);
	CHECK_EQ(start.args[6], 1u | 2u | 4u | 8u | 16u);
	CHECK_EQ(start.args[7], 9u);  // ignored entity
	CHECK_EQ(start.args[8], 7u);
	sc.rayHit = false;
	CHECK(!g.castRay({0, 0, 0}, {1, 0, 0}, 0).hit);
	g_handler = nullptr;
}

TEST(natives_text_and_online_and_weapon_damage) {
	Scene sc;
	g_handler = sc.handler();
	shv::ShvGame g;
	g.notify("Minecraft connected");
	CHECK(sc.lastNotify == "Minecraft connected");
	std::string longText(300, 'x');
	g.drawText(0.1f, 0.2f, 0.3f, longText.c_str());
	CHECK_EQ(sc.lastNotify.size(), 99u);  // clipped to what one text component can hold
	CHECK(!g.onlineSession());
	sc.online = true;
	CHECK(g.onlineSession());
	CHECK_NEAR(g.weaponDamage(0x1B06D571), 33.0, 1e-6);
	g_handler = nullptr;
}

TEST(joaat_matches_known_gta_hashes) {
	CHECK_EQ(opt::joaatHash("weapon_pistol"), 0x1B06D571u);
	CHECK_EQ(opt::joaatHash("WEAPON_PISTOL"), 0x1B06D571u);  // case-insensitive
	CHECK_EQ(opt::joaatHash("adder"), 0xB779A091u);
}

int main(int argc, char** argv) { return tst::runAll(argc > 1 ? argv[1] : nullptr); }
