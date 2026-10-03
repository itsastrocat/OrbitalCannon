// Shared-memory mapping, one implementation per platform behind one interface.
//   Windows: named pagefile-backed section "Local\<name>" (+ a named auto-reset event used to wake the Java reader).
//   POSIX:   MAP_SHARED file under /dev/shm (or $TMPDIR). This path exists so the whole system, including the real
//            Java mod, can be exercised end to end on Linux CI without GTA V.
#pragma once

#include <string>

namespace ocp {

class SharedMapping {
public:
	SharedMapping() = default;
	~SharedMapping() { close(); }
	SharedMapping(const SharedMapping&) = delete;
	SharedMapping& operator=(const SharedMapping&) = delete;
	SharedMapping(SharedMapping&& o) noexcept { *this = static_cast<SharedMapping&&>(o); }
	SharedMapping& operator=(SharedMapping&& o) noexcept;

	// Creates or opens the mapping of kTotalSize bytes. Idempotent across processes and start order.
	bool open(const std::string& name, std::string* error);
	void close();

	void* data() const { return base_; }
	bool isOpen() const { return base_ != nullptr; }

	// Wakes a reader blocked on the peer event right after we published to ring B. No-op on POSIX.
	void signalPeer();

private:
	void* base_ = nullptr;
#if defined(_WIN32)
	void* mapping_ = nullptr;
	void* event_ = nullptr;
#else
	int fd_ = -1;
#endif
};

// Resolves a logical name to the object name used by the platform ("Local\<name>" / "/dev/shm/<name>").
std::string resolveMappingName(const std::string& name);

}  // namespace ocp
