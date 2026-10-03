// Typed wrappers over ScriptHookV's native-invocation primitives. Declared here exactly as the SDK's main.h does,
// so the same translation unit links against ScriptHookV.lib in production and against a recording stub in tests.
#pragma once

#include <cstdint>
#include <cstring>
#include <type_traits>

#include "main.h"  // ScriptHookV SDK: nativeInit, nativePush64, nativeCall (tests/shv_stub/main.h stands in for it)

namespace shv {

// GTA passes Vector3 through script memory as three 8-byte slots, each holding a float in its low 4 bytes.
struct alignas(8) ScriptVec3 {
	float x;
	uint32_t padX;
	float y;
	uint32_t padY;
	float z;
	uint32_t padZ;
};
static_assert(sizeof(ScriptVec3) == 24);

namespace detail {
template <class T>
inline void pushArg(T v) {
	static_assert(sizeof(T) <= 8 && std::is_trivially_copyable_v<T>);
	uint64_t slot = 0;  // zero-extended: a float or int lives in the low bytes, exactly like the SDK's nativePush<T>
	std::memcpy(&slot, &v, sizeof(T));
	nativePush64(slot);
}
template <class T>
inline void pushArg(T* p) {  // out-parameters are passed as raw addresses
	nativePush64(static_cast<uint64_t>(reinterpret_cast<uintptr_t>(p)));
}
inline void pushArg(const char* s) { nativePush64(static_cast<uint64_t>(reinterpret_cast<uintptr_t>(s))); }
inline void pushArg(char* s) { nativePush64(static_cast<uint64_t>(reinterpret_cast<uintptr_t>(s))); }
}  // namespace detail

template <class R = void, class... A>
inline R call(uint64_t hash, A... args) {
	nativeInit(hash);
	(detail::pushArg(args), ...);
	const auto* ret = nativeCall();  // PUINT64: three consecutive 8-byte result slots
	if constexpr (!std::is_void_v<R>) {
		static_assert(sizeof(R) <= 24 && std::is_trivially_copyable_v<R>);
		R out{};
		std::memcpy(&out, ret, sizeof(R));
		return out;
	}
}

}  // namespace shv
