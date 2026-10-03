#pragma once

#include <cstdint>

namespace opt {

// GTA's joaat: Jenkins one-at-a-time over the lower-cased bytes. Same value GET_HASH_KEY returns, with no native call.
inline uint32_t joaatHash(const char* s) {
	uint32_t h = 0;
	for (; *s; ++s) {
		const char c = (*s >= 'A' && *s <= 'Z') ? static_cast<char>(*s + 32) : *s;
		h += static_cast<uint8_t>(c);
		h += h << 10;
		h ^= h >> 6;
	}
	h += h << 3;
	h ^= h >> 11;
	h += h << 15;
	return h;
}

}  // namespace opt
