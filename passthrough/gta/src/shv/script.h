#pragma once

#include "main.h"

namespace shv {

// Registered with ScriptHookV by dllmain.cpp.
void ScriptMain();
void OnKeyboard(DWORD key, WORD repeats, BYTE scanCode, BOOL isExtended, BOOL isWithAlt, BOOL wasDownBefore, BOOL isUpNow);
void SetModule(HMODULE module);

}  // namespace shv
