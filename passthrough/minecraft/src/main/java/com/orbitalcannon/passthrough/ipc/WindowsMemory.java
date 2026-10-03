package com.orbitalcannon.passthrough.ipc;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Named pagefile-backed section + wake event, via kernel32 downcalls.
 *
 * <p>Creation is "create or open": whichever process calls {@code CreateFileMappingW} first creates the section, the
 * other opens it, and the init handshake in {@link Bridge} makes the start order irrelevant. Default security applies,
 * so both processes must run as the same user at the same elevation level.
 *
 * <p>The FFM linker marks these calls as restricted. Run the JVM with {@code --enable-native-access=ALL-UNNAMED} to
 * silence the one-time warning.
 */
final class WindowsMemory implements SharedMemory {
	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	private static final int WAIT_OBJECT_0 = 0;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
	private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
	private static final VarHandle LAST_ERROR = CAPTURE.varHandle(PathElement.groupElement("GetLastError"));

	private static final MethodHandle CREATE_FILE_MAPPING = LINKER.downcallHandle(
		K32.find("CreateFileMappingW").orElseThrow(),
		FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
		Linker.Option.captureCallState("GetLastError"));
	private static final MethodHandle MAP_VIEW_OF_FILE = LINKER.downcallHandle(
		K32.find("MapViewOfFile").orElseThrow(),
		FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, java.lang.foreign.ValueLayout.JAVA_LONG),
		Linker.Option.captureCallState("GetLastError"));
	private static final MethodHandle UNMAP_VIEW_OF_FILE = LINKER.downcallHandle(
		K32.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
	private static final MethodHandle CLOSE_HANDLE = LINKER.downcallHandle(
		K32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
	private static final MethodHandle CREATE_EVENT = LINKER.downcallHandle(
		K32.find("CreateEventW").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
	private static final MethodHandle WAIT_FOR_SINGLE_OBJECT = LINKER.downcallHandle(
		K32.find("WaitForSingleObject").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

	private final String objectName;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment mappingHandle;
	private final MemorySegment view;
	private final MemorySegment segment;
	private final MemorySegment eventHandle; // may be NULL: the reader then falls back to polling

	WindowsMemory(String name) throws IOException {
		this.objectName = "Local\\" + name;
		try {
			MemorySegment capture = arena.allocate(CAPTURE);
			MemorySegment wname = arena.allocateFrom(objectName, StandardCharsets.UTF_16LE); // NUL terminated (2 bytes)
			long size = Protocol.TOTAL_SIZE;
			mappingHandle = (MemorySegment) CREATE_FILE_MAPPING.invokeExact(capture, MemorySegment.ofAddress(-1L), MemorySegment.NULL,
				PAGE_READWRITE, (int) (size >>> 32), (int) size, wname);
			if (mappingHandle.equals(MemorySegment.NULL)) {
				throw new IOException("CreateFileMappingW(" + objectName + ") failed, GetLastError=" + (int) LAST_ERROR.get(capture, 0L));
			}
			view = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(capture, mappingHandle, FILE_MAP_ALL_ACCESS, 0, 0, size);
			if (view.equals(MemorySegment.NULL)) {
				// invokeExact is signature-polymorphic: the call-site return type must be cast even when unused.
				int closed = (int) CLOSE_HANDLE.invokeExact(mappingHandle);
				throw new IOException("MapViewOfFile failed (close=" + closed + "), GetLastError=" + (int) LAST_ERROR.get(capture, 0L));
			}
			segment = view.reinterpret(size);
			MemorySegment ename = arena.allocateFrom(objectName + "_gta2mc", StandardCharsets.UTF_16LE);
			eventHandle = (MemorySegment) CREATE_EVENT.invokeExact(MemorySegment.NULL, 0, 0, ename); // auto-reset, unsignalled
		} catch (IOException e) {
			arena.close();
			throw e;
		} catch (Throwable t) {
			arena.close();
			throw new IOException("kernel32 call failed: " + t, t);
		}
	}

	@Override
	public MemorySegment segment() {
		return segment;
	}

	@Override
	public boolean hasSignal() {
		return !eventHandle.equals(MemorySegment.NULL);
	}

	@Override
	public boolean awaitSignal(long timeoutMicros) {
		long ms = Math.max(1, (timeoutMicros + 999) / 1000);
		try {
			if (eventHandle.equals(MemorySegment.NULL)) {
				Thread.sleep(ms);
				return false;
			}
			return (int) WAIT_FOR_SINGLE_OBJECT.invokeExact(eventHandle, (int) Math.min(ms, Integer.MAX_VALUE)) == WAIT_OBJECT_0;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public String describe() {
		return "named section " + objectName;
	}

	@Override
	public void close() {
		try {
			int ignored = (int) UNMAP_VIEW_OF_FILE.invokeExact(view);
			ignored = (int) CLOSE_HANDLE.invokeExact(mappingHandle);
			if (!eventHandle.equals(MemorySegment.NULL)) ignored = (int) CLOSE_HANDLE.invokeExact(eventHandle);
		} catch (Throwable ignored) {
			// process is going away or the handles are already closed
		}
		arena.close();
	}
}
