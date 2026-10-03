package com.orbitalcannon.passthrough.ipc;

import static com.orbitalcannon.passthrough.ipc.Protocol.MAX_PAYLOAD;
import static com.orbitalcannon.passthrough.ipc.Protocol.MSG_HEADER_SIZE;
import static com.orbitalcannon.passthrough.ipc.Protocol.MSG_PAD;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_CAPACITY;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_HEAD;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_MASK;
import static com.orbitalcannon.passthrough.ipc.Protocol.R_TAIL;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Single-consumer end of the SPSC ring. Mirrors {@code ocp::RingReader}.
 *
 * <p>Everything in the mapping is untrusted input written by another process, so every record is bounds-checked and
 * a malformed or torn record makes the reader resynchronise to the producer's head instead of following a bad length.
 */
public final class RingReader {
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle(); // coordinates: (segment, byteOffset)

	/** Receives each record's payload in place: {@code seg} is the mapped region, the payload is {@code [off, off+len)}. */
	@FunctionalInterface
	public interface Handler {
		void onMessage(int type, MemorySegment seg, long payloadOff, int len);
	}

	private final MemorySegment seg;
	private final long ctl;
	private final long data;
	private final int cap;
	private final int mask;
	private long cursor;
	private long corrupt;

	public RingReader(MemorySegment seg, long ctlOff, long dataOff) {
		this.seg = seg;
		this.ctl = ctlOff;
		this.data = dataOff;
		this.cap = seg.get(JAVA_INT_UNALIGNED, ctlOff + R_CAPACITY);
		this.mask = seg.get(JAVA_INT_UNALIGNED, ctlOff + R_MASK);
		if (cap <= 0 || (cap & (cap - 1)) != 0 || mask != cap - 1) {
			throw new IllegalStateException("ring control block is not initialised (capacity=" + cap + ", mask=" + mask + ")");
		}
		this.cursor = (long) LONG.getAcquire(seg, ctlOff + R_TAIL);
	}

	public long corruptCount() {
		return corrupt;
	}

	/** Bytes waiting for us right now. Safe to call from any thread (it only reads the two cursors). */
	public long available() {
		return (long) LONG.getAcquire(seg, ctl + R_HEAD) - (long) LONG.getAcquire(seg, ctl + R_TAIL);
	}

	/** Discards everything currently in the ring, e.g. leftovers of a dead peer session. */
	public void skipAll() {
		cursor = (long) LONG.getAcquire(seg, ctl + R_HEAD);
		LONG.setRelease(seg, ctl + R_TAIL, cursor);
	}

	/** Delivers up to {@code max} records in order and frees their space. Returns how many were delivered. */
	public int drain(Handler handler, int max) {
		long head = (long) LONG.getAcquire(seg, ctl + R_HEAD);
		int delivered = 0;
		while (cursor != head && delivered < max) {
			long avail = head - cursor;
			int pos = (int) (cursor & mask);
			long p = data + pos;
			int type = Short.toUnsignedInt(seg.get(JAVA_SHORT_UNALIGNED, p));
			long len = Integer.toUnsignedLong(seg.get(JAVA_INT_UNALIGNED, p + 4));
			long rec = MSG_HEADER_SIZE + ((len + 7) & ~7L);
			if (rec > avail || pos + rec > cap || (type != MSG_PAD && len > MAX_PAYLOAD)) {
				corrupt++;
				cursor = head; // malformed or torn: resynchronise
				break;
			}
			if (type != MSG_PAD) {
				handler.onMessage(type, seg, p + MSG_HEADER_SIZE, (int) len);
				delivered++;
			}
			cursor += rec;
		}
		LONG.setRelease(seg, ctl + R_TAIL, cursor);
		return delivered;
	}
}
