package com.orbitalcannon.passthrough.ipc;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RingTest {
	private static final long CTL = 0, DATA = 4096;

	private static MemorySegment ring(Arena arena, int cap) {
		MemorySegment seg = arena.allocate(DATA + cap, 4096);
		seg.set(JAVA_INT_UNALIGNED, CTL + Protocol.R_CAPACITY, cap);
		seg.set(JAVA_INT_UNALIGNED, CTL + Protocol.R_MASK, cap - 1);
		return seg;
	}

	@Test
	void wrapsPadsAndReportsFull() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment seg = ring(arena, 1024);
			RingWriter w = new RingWriter(seg, CTL, DATA);
			RingReader r = new RingReader(seg, CTL, DATA);
			int written = 0, got = 0, expect = 0;
			for (;; written++) {
				int len = 37 + (written % 5) * 8;
				long p = w.begin(100, len);
				if (p < 0) break;
				for (int i = 0; i < len; i++) seg.set(JAVA_BYTE, p + i, (byte) written);
				w.commit();
			}
			assertTrue(written >= 3);
			assertTrue(w.droppedCount() >= 1);
			int[] next = {0};
			for (int round = 0; round < 500; round++) {
				got += r.drain((type, s, off, len) -> {
					assertEquals(100, type);
					assertEquals(37 + (next[0] % 5) * 8, len);
					for (int i = 0; i < len; i++) assertEquals((byte) next[0], s.get(JAVA_BYTE, off + i));
					next[0]++;
				}, Integer.MAX_VALUE);
				for (int k = 0; k < 3; k++) {
					int len = 37 + (written % 5) * 8;
					long p = w.begin(100, len);
					if (p < 0) break;
					for (int i = 0; i < len; i++) seg.set(JAVA_BYTE, p + i, (byte) written);
					w.commit();
					written++;
				}
			}
			got += r.drain((t, s, o, l) -> next[0]++, Integer.MAX_VALUE);
			assertEquals(written, got);
			assertEquals(0, r.corruptCount());
		}
	}

	@Test
	void uncommittedRecordsAreInvisibleAndOversizeIsRejected() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment seg = ring(arena, 4096);
			RingWriter w = new RingWriter(seg, CTL, DATA);
			RingReader r = new RingReader(seg, CTL, DATA);
			assertEquals(-1, w.begin(1, Protocol.MAX_PAYLOAD + 1));
			assertEquals(-1, w.begin(1, -1));
			assertTrue(w.begin(7, 8) >= 0);
			assertEquals(0, r.drain((t, s, o, l) -> {}, 10));
			w.commit();
			assertEquals(1, r.drain((t, s, o, l) -> {}, 10));
		}
	}

	@Test
	void corruptRecordMakesTheReaderResynchroniseInsteadOfFollowingABadLength() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment seg = ring(arena, 4096);
			RingWriter w = new RingWriter(seg, CTL, DATA);
			RingReader r = new RingReader(seg, CTL, DATA);
			long p = w.begin(1, 8);
			seg.set(JAVA_LONG_UNALIGNED, p, 42);
			w.commit();
			// A hostile or restarted peer scribbles an absurd length into the next header and publishes it.
			seg.set(JAVA_INT_UNALIGNED, DATA + 16 + 4, 0xFFFFFFF0);
			seg.set(JAVA_INT_UNALIGNED, DATA + 16, 1);
			VarHandle h = java.lang.foreign.ValueLayout.JAVA_LONG.varHandle();
			h.setRelease(seg, CTL + Protocol.R_HEAD, 16L + 24L);
			int[] delivered = {0};
			r.drain((t, s, o, l) -> delivered[0]++, 10);
			assertEquals(1, delivered[0]);
			assertEquals(1, r.corruptCount());
			assertEquals(0, r.available());
			RingWriter w2 = new RingWriter(seg, CTL, DATA);
			long q = w2.begin(2, 8);
			seg.set(JAVA_LONG_UNALIGNED, q, 7);
			w2.commit();
			int[] ok = {0};
			r.drain((t, s, o, l) -> ok[0] += t == 2 ? 1 : 0, 10);
			assertEquals(1, ok[0]);
		}
	}

	@Test
	void twoThreadStressPreservesOrderAndContent() throws Exception {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment seg = ring(arena, 1 << 16);
			final long n = 1_000_000;
			AtomicBoolean bad = new AtomicBoolean();
			Thread producer = new Thread(() -> {
				RingWriter w = new RingWriter(seg, CTL, DATA);
				Random rng = new Random(99);
				for (long i = 0; i < n; ) {
					int len = 8 + rng.nextInt(40) * 8;
					long p = w.begin((int) (1 + i % 3), len);
					if (p < 0) {
						Thread.onSpinWait();
						continue;
					}
					seg.set(JAVA_LONG_UNALIGNED, p, i);
					for (int k = 8; k < len; k++) seg.set(JAVA_BYTE, p + k, (byte) (i + k));
					i++;
					if ((i & 7) == 0 || i == n) w.commit();
				}
				w.commit();
			});
			Thread consumer = new Thread(() -> {
				RingReader r = new RingReader(seg, CTL, DATA);
				long[] expect = {0};
				while (expect[0] < n && !bad.get()) {
					r.drain((type, s, off, len) -> {
						long seq = s.get(JAVA_LONG_UNALIGNED, off);
						if (seq != expect[0] || type != 1 + seq % 3) bad.set(true);
						for (int k = 8; k < len; k++) {
							if (s.get(JAVA_BYTE, off + k) != (byte) (seq + k)) bad.set(true);
						}
						expect[0]++;
					}, Integer.MAX_VALUE);
				}
				if (expect[0] != n) bad.set(true);
			});
			producer.start();
			consumer.start();
			producer.join();
			consumer.join();
			assertFalse(bad.get());
		}
	}

	@Test
	void seqlockWriterAndReaderNeverProduceTornState() throws Exception {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment seg = arena.allocate(4096, 4096);
			PlayerState.Writer w = new PlayerState.Writer(seg, 0);
			PlayerState.Reader r = new PlayerState.Reader(seg, 0);
			AtomicBoolean stop = new AtomicBoolean();
			Thread writer = new Thread(() -> {
				PlayerState s = new PlayerState();
				for (long i = 1; !stop.get(); i++) {
					s.frame = i;
					s.px = s.py = s.pz = i;
					s.vx = s.vy = s.vz = i % 100000;
					s.weaponHash = (int) i;
					w.write(s);
				}
			});
			writer.start();
			PlayerState got = new PlayerState();
			long reads = 0, torn = 0, backwards = 0, last = 0;
			long end = System.nanoTime() + 500_000_000L;
			while (System.nanoTime() < end) {
				if (!r.read(got)) continue;
				reads++;
				if (got.px != got.frame || got.pz != got.frame || got.weaponHash != (int) got.frame || got.vy != (float) (got.frame % 100000)) torn++;
				if (got.frame < last) backwards++;
				last = got.frame;
			}
			stop.set(true);
			writer.join();
			assertTrue(reads > 1000, "reads=" + reads);
			assertEquals(0, torn);
			assertEquals(0, backwards);
		}
	}

	@Test
	void seqlockReaderReportsNothingBeforeTheFirstWrite() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment seg = arena.allocate(4096, 4096);
			assertFalse(new PlayerState.Reader(seg, 0).read(new PlayerState()));
		}
	}
}
