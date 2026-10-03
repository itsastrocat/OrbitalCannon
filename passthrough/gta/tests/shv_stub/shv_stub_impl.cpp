// Link-time stand-in for ScriptHookV.lib so the .asi can be built and linked as a real PE image in CI without the
// proprietary SDK. These do nothing; the unit tests use their own recording implementations.
#include "main.h"

void scriptWait(DWORD) {}
void scriptRegister(HMODULE, void (*)()) {}
void scriptUnregister(HMODULE) {}
void keyboardHandlerRegister(KeyboardHandler) {}
void keyboardHandlerUnregister(KeyboardHandler) {}
void nativeInit(UINT64) {}
void nativePush64(UINT64) {}
PUINT64 nativeCall() {
	static UINT64 slots[4];
	return slots;
}
