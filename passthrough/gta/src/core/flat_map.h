// Open-addressing hash map for 64-bit keys: linear probing, power-of-two capacity, load factor <= 0.5, and
// backward-shift deletion (no tombstones, so lookups never degrade). Built for the hot per-frame voxel and entity
// lookups, where std::unordered_map's node allocations and pointer chasing are measurable.
#pragma once

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace opt {

template <class V>
class FlatMap64 {
public:
	explicit FlatMap64(size_t initial_capacity = 256) { rehash(roundUp(initial_capacity * 2)); }

	size_t size() const { return size_; }
	bool empty() const { return size_ == 0; }

	V* find(uint64_t key) {
		for (size_t i = home(key);; i = (i + 1) & mask_) {
			Slot& s = table_[i];
			if (!s.used) return nullptr;
			if (s.key == key) return &s.value;
		}
	}
	const V* find(uint64_t key) const { return const_cast<FlatMap64*>(this)->find(key); }
	bool contains(uint64_t key) const { return find(key) != nullptr; }

	// Inserts or overwrites. Returns true if the key was new.
	bool put(uint64_t key, V value) {
		if ((size_ + 1) * 2 > table_.size()) rehash(table_.size() * 2);
		for (size_t i = home(key);; i = (i + 1) & mask_) {
			Slot& s = table_[i];
			if (!s.used) {
				s.used = true;
				s.key = key;
				s.value = std::move(value);
				++size_;
				return true;
			}
			if (s.key == key) {
				s.value = std::move(value);
				return false;
			}
		}
	}

	bool erase(uint64_t key) {
		size_t i = home(key);
		for (;; i = (i + 1) & mask_) {
			if (!table_[i].used) return false;
			if (table_[i].key == key) break;
		}
		// Backward shift: pull later members of the probe chain into the hole so chains stay contiguous.
		size_t hole = i;
		for (size_t j = (i + 1) & mask_;; j = (j + 1) & mask_) {
			if (!table_[j].used) break;
			const size_t h = home(table_[j].key);
			// Move j into the hole unless its home lies cyclically within (hole, j].
			const bool stays = (hole <= j) ? (hole < h && h <= j) : (hole < h || h <= j);
			if (!stays) {
				table_[hole] = std::move(table_[j]);
				hole = j;
			}
		}
		table_[hole].used = false;
		--size_;
		return true;
	}

	void clear() {
		for (Slot& s : table_) s.used = false;
		size_ = 0;
	}

	template <class F>
	void forEach(F&& f) {
		for (Slot& s : table_) {
			if (s.used) f(s.key, s.value);
		}
	}
	template <class F>
	void forEach(F&& f) const {
		for (const Slot& s : table_) {
			if (s.used) f(s.key, s.value);
		}
	}

private:
	struct Slot {
		uint64_t key = 0;
		V value{};
		bool used = false;
	};

	static size_t roundUp(size_t n) {
		size_t c = 16;
		while (c < n) c <<= 1;
		return c;
	}
	static uint64_t mix(uint64_t k) {  // murmur3 finaliser
		k ^= k >> 33;
		k *= 0xff51afd7ed558ccdULL;
		k ^= k >> 33;
		k *= 0xc4ceb9fe1a85ec53ULL;
		k ^= k >> 33;
		return k;
	}
	size_t home(uint64_t key) const { return static_cast<size_t>(mix(key)) & mask_; }

	void rehash(size_t new_cap) {
		std::vector<Slot> old;
		old.swap(table_);
		table_.assign(new_cap, Slot{});
		mask_ = new_cap - 1;
		size_ = 0;
		for (Slot& s : old) {
			if (s.used) put(s.key, std::move(s.value));
		}
	}

	std::vector<Slot> table_;
	size_t mask_ = 0;
	size_t size_ = 0;
};

}  // namespace opt
