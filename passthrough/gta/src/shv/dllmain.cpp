#include <windows.h>

#include "script.h"

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID /*reserved*/) {
	switch (reason) {
		case DLL_PROCESS_ATTACH:
			DisableThreadLibraryCalls(hModule);
			shv::SetModule(hModule);
			scriptRegister(hModule, shv::ScriptMain);
			keyboardHandlerRegister(shv::OnKeyboard);
			break;
		case DLL_PROCESS_DETACH:
			keyboardHandlerUnregister(shv::OnKeyboard);
			scriptUnregister(hModule);
			break;
		default:
			break;
	}
	return TRUE;
}
