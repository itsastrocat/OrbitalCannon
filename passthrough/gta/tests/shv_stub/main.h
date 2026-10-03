// Stand-in for the ScriptHookV SDK's main.h, declaring exactly the entry points this mod uses with the same
// signatures. It lets natives.cpp / script.cpp / dllmain.cpp be compiled, linked and unit tested without the
// proprietary SDK. Production builds put the real SDK's inc/ directory on the include path instead.
#pragma once

#if defined(_WIN32)
#include <windows.h>
#else
#include <cstdint>
typedef unsigned long DWORD;
typedef unsigned short WORD;
typedef unsigned char BYTE;
typedef int BOOL;
typedef void* HMODULE;
typedef uint64_t UINT64;  // 'unsigned long long' on Windows, where uint64_t is the same type
typedef UINT64* PUINT64;
#endif

void scriptWait(DWORD time);
void scriptRegister(HMODULE module, void (*function)());
void scriptUnregister(HMODULE module);
typedef void (*KeyboardHandler)(DWORD key, WORD repeats, BYTE scanCode, BOOL isExtended, BOOL isWithAlt, BOOL wasDownBefore, BOOL isUpNow);
void keyboardHandlerRegister(KeyboardHandler handler);
void keyboardHandlerUnregister(KeyboardHandler handler);
void nativeInit(UINT64 hash);
void nativePush64(UINT64 val);
PUINT64 nativeCall();
