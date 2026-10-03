// Native hashes used by the passthrough. Every entry was cross-checked against the FiveM natives reference
// (https://runtime.fivem.net/doc/natives.json) by tools/gen_native_table.py, which also emits the parameter-count
// table that tests/test_natives.cpp enforces on every call this mod makes.
#pragma once

#include <cstdint>

namespace shv::nat {

// STREAMING
inline constexpr uint64_t REQUEST_MODEL = 0x963D27A58DF860AC;               // void (Hash model)
inline constexpr uint64_t HAS_MODEL_LOADED = 0x98A4EB5D89A0C952;            // BOOL (Hash model)
inline constexpr uint64_t SET_MODEL_AS_NO_LONGER_NEEDED = 0xE532F5D78798DAAB;  // void (Hash model)
inline constexpr uint64_t IS_MODEL_VALID = 0xC0296A2EDF545E92;              // BOOL (Hash model)
inline constexpr uint64_t IS_MODEL_IN_CDIMAGE = 0x35B9E0803292B641;         // BOOL (Hash model)
// MISC
inline constexpr uint64_t GET_MODEL_DIMENSIONS = 0x03E8D3D5F549087A;        // void (Hash, Vector3* min, Vector3* max)
// OBJECT
inline constexpr uint64_t CREATE_OBJECT_NO_OFFSET = 0x9A294B2138ABB884;     // Object (Hash, x, y, z, isNetwork, netMissionEntity, doorFlag)
inline constexpr uint64_t DELETE_OBJECT = 0x539E0AE3E6634B9F;               // void (Object*)
inline constexpr uint64_t SET_OBJECT_TEXTURE_VARIATION = 0x971DA0055324D033;  // void (Object, int)  [_SET_OBJECT_TEXTURE_VARIATION]
// ENTITY
inline constexpr uint64_t DOES_ENTITY_EXIST = 0x7239B21A38F536BA;           // BOOL (Entity)
inline constexpr uint64_t FREEZE_ENTITY_POSITION = 0x428CA6DBD1094446;      // void (Entity, BOOL)
inline constexpr uint64_t SET_ENTITY_COLLISION = 0x1A9205C1B9EE827F;        // void (Entity, BOOL toggle, BOOL keepPhysics)
inline constexpr uint64_t SET_ENTITY_COORDS_NO_OFFSET = 0x239A3351AC1DA385; // void (Entity, x, y, z, keepTasks, keepIK, doWarp)
inline constexpr uint64_t SET_ENTITY_LOAD_COLLISION_FLAG = 0x0DC7CABAB1E9B67E;  // void (Entity, BOOL)
inline constexpr uint64_t SET_ENTITY_INVINCIBLE = 0x3882114BDE571AD4;       // void (Entity, BOOL)
inline constexpr uint64_t SET_ENTITY_CAN_BE_DAMAGED = 0x1760FFA8AB074D66;   // void (Entity, BOOL)
inline constexpr uint64_t SET_ENTITY_LOD_DIST = 0x5927F96A78577363;         // void (Entity, int)
inline constexpr uint64_t SET_ENTITY_VISIBLE = 0xEA1C610A04DB6BBB;          // void (Entity, BOOL, BOOL)
inline constexpr uint64_t GET_ENTITY_COORDS = 0x3FEF770D40960D5A;           // Vector3 (Entity, BOOL alive)
inline constexpr uint64_t GET_ENTITY_VELOCITY = 0x4805D2B1D8CF94A9;         // Vector3 (Entity)
inline constexpr uint64_t GET_ENTITY_HEADING = 0xE83D4F9BA2A38914;          // float (Entity)
inline constexpr uint64_t GET_ENTITY_MODEL = 0x9F47B058362C84B5;            // Hash (Entity)
inline constexpr uint64_t GET_ENTITY_SPEED = 0xD5037BA82E12416F;            // float (Entity)
inline constexpr uint64_t IS_ENTITY_DEAD = 0x5F9532F3B5CC2551;              // BOOL (Entity)
// PLAYER / PED
inline constexpr uint64_t PLAYER_PED_ID = 0xD80958FC74E988A6;               // Ped ()
inline constexpr uint64_t IS_PED_SHOOTING = 0x34616828CD07F1A1;             // BOOL (Ped)
inline constexpr uint64_t IS_PED_IN_ANY_VEHICLE = 0x997ABD671D25CA0B;       // BOOL (Ped, BOOL atGetIn)
inline constexpr uint64_t GET_VEHICLE_PED_IS_IN = 0x9A9112A0FE9A4713;       // Vehicle (Ped, BOOL lastVehicle)
inline constexpr uint64_t IS_PED_ON_FOOT = 0x01FEE67DB37F59B2;              // BOOL (Ped)
inline constexpr uint64_t IS_PED_RAGDOLL = 0x47E4E977581C5B55;              // BOOL (Ped)
// WEAPON
inline constexpr uint64_t GET_SELECTED_PED_WEAPON = 0x0A6DB4965674D243;     // Hash (Ped)
inline constexpr uint64_t GET_PED_LAST_WEAPON_IMPACT_COORD = 0x6C4D0409BA1A2BC2;  // BOOL (Ped, Vector3*)
inline constexpr uint64_t GET_WEAPON_DAMAGE = 0x3133B907D8B32053;           // float (Hash weapon, Hash component)
// CAM
inline constexpr uint64_t GET_GAMEPLAY_CAM_COORD = 0x14D6F5678D8F1B37;      // Vector3 ()
inline constexpr uint64_t GET_GAMEPLAY_CAM_ROT = 0x837765A25378F0BB;        // Vector3 (int rotationOrder)
// SHAPETEST
inline constexpr uint64_t START_EXPENSIVE_SYNCHRONOUS_SHAPE_TEST_LOS_PROBE = 0x377906D8A31E5586;  // int (x1,y1,z1,x2,y2,z2,flags,entity,p8)
inline constexpr uint64_t GET_SHAPE_TEST_RESULT = 0x3D87450E15D98694;       // int (handle, BOOL* hit, Vector3* end, Vector3* normal, Entity* hitEntity)
// NETWORK
inline constexpr uint64_t NETWORK_IS_GAME_IN_PROGRESS = 0x10FAB35428CCC9D7;  // BOOL ()
// HUD
inline constexpr uint64_t BEGIN_TEXT_COMMAND_THEFEED_POST = 0x202709F4C58A0424;  // void (char*)
inline constexpr uint64_t END_TEXT_COMMAND_THEFEED_POST_TICKER = 0x2ED7843F8F801023;  // int (BOOL, BOOL)
inline constexpr uint64_t ADD_TEXT_COMPONENT_SUBSTRING_PLAYER_NAME = 0x6C188BE134E074AA;  // void (char*)
inline constexpr uint64_t BEGIN_TEXT_COMMAND_DISPLAY_TEXT = 0x25FBB336DF1804CB;  // void (char*)
inline constexpr uint64_t END_TEXT_COMMAND_DISPLAY_TEXT = 0xCD015E5BB0D96A57;    // void (float x, float y, int p2)
inline constexpr uint64_t SET_TEXT_SCALE = 0x07C837F9A01C34C9;              // void (float, float)
inline constexpr uint64_t SET_TEXT_FONT = 0x66E0276CC5F6B9DA;               // void (int)
inline constexpr uint64_t SET_TEXT_COLOUR = 0xBE6B23FFA53FB442;             // void (int, int, int, int)
inline constexpr uint64_t SET_TEXT_OUTLINE = 0x2513DFB0FB8400FE;            // void ()

}  // namespace shv::nat
