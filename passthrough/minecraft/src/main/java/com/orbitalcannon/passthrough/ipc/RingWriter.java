package com.orbitalcannon.passthrough.ipc;

import static com.orbitalcannon.passthrough.ipc.Protocol.MAX_PAYLOAD;
import static com.orbitalcannon.passthrough.ipc.Protocol.MSG_HEADER_SIZE;
import static com.orbitalcannon.passthrough.ipc.Protocol.MSG_PAD;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_CAPACITY;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_DROPPED;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_HEAD;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_MASK;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_RECORDS;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_TAIL;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Single-producer end of a lock-free SPSC byte ring living in shared memory. Mirrors {@code ocp::RingWriter}.
 *
 * <p>Records are reserved with {@link #begin}, filled in place (zero copy: the caller writes straight into the
 * mapped segment at the returned offset) and published in batches by {@link #commit}, which is a single release store
 * of the head cursor. The consumer can never observe a half-written record.
 *
 * <p>Not thread safe: exactly one thread may produce. The Minecraft server thread is that thread.
 */
public final class RingWriter {
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle(); // coordinates: (segment, byteOffset)

	private final MemorySegment seg;
	private final long ctl;
	private final long data;
	private final int cap;
	private final int mask;

	private long pending; // next write cursor, including uncommitted records
	private long cachedTail;
	private int unpublished;

	public RingWriter(MemorySegment seg, long ctlOff, long dataOff) {
		this.seg = seg;
		this.ctl = ctlOff;
		this.data = dataOff;
		this.cap = seg.get(JAVA_INT_UNALIGNED, ctlOff + R_CAPACITY);
		this.mask = seg.get(JAVA_INT_UNALIGNED, ctlOff + R_MASK);
		if (cap <= 0 || (cap & (cap - 1)) != 0 || mask != cap - 1) {
			throw new IllegalStateException("ring control block is not initialised (capacity=" + cap + ", mask=" + mask + ")");
		}
		this.pending = (long) LONG.getAcquire(seg, ctlOff + R_HEAD);
		this.cachedTail = (long) LONG.getAcquire(seg, ctlOff + R_TAIL);
	}

	public MemorySegment segment() {
		return seg;
	}

	/**
	 * Reserves one record. Returns the absolute offset of its payload within {@link #segment()}, or -1 if the ring is
	 * full or {@code len} is out of range. The record stays invisible until {@link #commit()}.
	 */
	public long begin(int type, int len) {
		if (len < 0 || len > MAX_PAYLOAD) return -1;
		int rec = Protocol.recordSize(len);
		int pos = (int) (pending & mask);
		int pad = pos + rec > cap ? cap - pos : 0;
		long need = (long) pad + rec;
		if (cap - (pending - cachedTail) < need) {
			cachedTail = (long) LONG.getAcquire(seg, ctl + R_TAIL);
			if (cap - (pending - cachedTail) < need) {
				seg.set(ValueLayout.JAVA_LONG_UNALIGNED, ctl + R_DROPPED, seg.get(ValueLayout.JAVA_LONG_UNALIGNED, ctl + R_DROPPED) + 1);
				return -1;
			}
		}
		if (pad != 0) {
			long p = data + pos;
			seg.set(JAVA_SHORT_UNALIGNED, p, (short) MSG_PAD);
			seg.set(JAVA_SHORT_UNALIGNED, p + 2, (short) 0);
			seg.set(JAVA_INT_UNALIGNED, p + 4, pad - MSG_HEADER_SIZE);
			pending += pad;
			pos = 0;
		}
		long p = data + pos;
		seg.set(JAVA_SHORT_UNALIGNED, p, (short) type);
		seg.set(JAVA_SHORT_UNALIGNED, p + 2, (short) 0);
		seg.set(JAVA_INT_UNALIGNED, p + 4, len);
		long payload = p + MSG_HEADER_SIZE;
		for (int i = len, end = Protocol.align8(len); i < end; i++) seg.set(JAVA_BYTE, payload + i, (byte) 0);
		pending += rec;
		unpublished++;
		return payload;
	}

	/** Makes every record reserved since the last commit visible to the consumer (one release store). */
	public void commit() {
		if (unpublished == 0) return;
		LONG.setRelease(seg, ctl + R_HEAD, pending);
		seg.set(ValueLayout.JAVA_LONG_UNALIGNED, ctl + R_RECORDS, seg.get(ValueLayout.JAVA_LONG_UNALIGNED, ctl + R_RECORDS) + unpublished);
		unpublished = 0;
	}

	/** Free bytes right now (refreshes the cached consumer cursor). */
	public long freeBytes() {
		cachedTail = (long) LONG.getAcquire(seg, ctl + R_TAIL);
		return cap - (pending - cachedTail);
	}

	/** True when records were reserved but not yet published. */
	public boolean hasUncommitted() {
		return unpublished != 0;
	}

	public long droppedCount() {
		return seg.get(ValueLayout.JAVA_LONG_UNALIGNED, ctl + R_DROPPED);
	}
}
