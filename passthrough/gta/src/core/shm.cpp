#include "shm.h"

#include <cerrno>
#include <cstring>

#include "../../../protocol/orbital_ipc.h"

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#endif

namespace ocp {

SharedMapping& SharedMapping::operator=(SharedMapping&& o) noexcept {
	if (this != &o) {
		close();
		base_ = o.base_;
		o.base_ = nullptr;
#if defined(_WIN32)
		mapping_ = o.mapping_;
		event_ = o.event_;
		o.mapping_ = o.event_ = nullptr;
#else
		fd_ = o.fd_;
		o.fd_ = -1;
#endif
	}
	return *this;
}

#if defined(_WIN32)

namespace {
std::wstring widen(const std::string& s) {
	if (s.empty()) return {};
	const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
	std::wstring w(static_cast<size_t>(n), L'\0');
	MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
	return w;
}
std::string winError(const char* what) {
	return std::string(what) + " failed, GetLastError=" + std::to_string(GetLastError());
}
}  // namespace

std::string resolveMappingName(const std::string& name) { return "Local\\" + name; }

bool SharedMapping::open(const std::string& name, std::string* error) {
	close();
	const std::wstring objName = widen(resolveMappingName(name));
	// Default security: only processes of the same user (and integrity level or higher) can open it. Run GTA V and
	// Minecraft at the same elevation.
	HANDLE m = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, static_cast<DWORD>(kTotalSize >> 32),
	                              static_cast<DWORD>(kTotalSize & 0xFFFFFFFFu), objName.c_str());
	if (!m) {
		if (error) *error = winError("CreateFileMappingW");
		return false;
	}
	void* p = MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, 0);
	if (!p) {
		if (error) *error = winError("MapViewOfFile");
		CloseHandle(m);
		return false;
	}
	mapping_ = m;
	base_ = p;
	const std::wstring evName = widen(resolveMappingName(name) + "_gta2mc");
	event_ = CreateEventW(nullptr, FALSE, FALSE, evName.c_str());  // optional: reader falls back to polling without it
	return true;
}

void SharedMapping::close() {
	if (base_) UnmapViewOfFile(base_);
	if (mapping_) CloseHandle(static_cast<HANDLE>(mapping_));
	if (event_) CloseHandle(static_cast<HANDLE>(event_));
	base_ = nullptr;
	mapping_ = nullptr;
	event_ = nullptr;
}

void SharedMapping::signalPeer() {
	if (event_) SetEvent(static_cast<HANDLE>(event_));
}

#else  // POSIX

std::string resolveMappingName(const std::string& name) {
	if (name.find('/') != std::string::npos) return name;
	struct stat st;
	if (stat("/dev/shm", &st) == 0 && S_ISDIR(st.st_mode)) return "/dev/shm/" + name;
	const char* tmp = std::getenv("TMPDIR");
	return std::string(tmp && *tmp ? tmp : "/tmp") + "/" + name;
}

bool SharedMapping::open(const std::string& name, std::string* error) {
	close();
	const std::string path = resolveMappingName(name);
	int fd = ::open(path.c_str(), O_RDWR | O_CREAT, 0600);
	if (fd < 0) {
		if (error) *error = "open(" + path + "): " + std::strerror(errno);
		return false;
	}
	struct stat st;
	if (fstat(fd, &st) != 0) {
		if (error) *error = std::string("fstat: ") + std::strerror(errno);
		::close(fd);
		return false;
	}
	// Grow only. Concurrent callers both grow to the same size, which is idempotent; new bytes read as zero.
	if (static_cast<size_t>(st.st_size) < kTotalSize && ftruncate(fd, static_cast<off_t>(kTotalSize)) != 0) {
		if (error) *error = std::string("ftruncate: ") + std::strerror(errno);
		::close(fd);
		return false;
	}
	void* p = mmap(nullptr, kTotalSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (p == MAP_FAILED) {
		if (error) *error = std::string("mmap: ") + std::strerror(errno);
		::close(fd);
		return false;
	}
	fd_ = fd;
	base_ = p;
	return true;
}

void SharedMapping::close() {
	if (base_) munmap(base_, kTotalSize);
	if (fd_ >= 0) ::close(fd_);
	base_ = nullptr;
	fd_ = -1;
}

void SharedMapping::signalPeer() {}

#endif

}  // namespace ocp
