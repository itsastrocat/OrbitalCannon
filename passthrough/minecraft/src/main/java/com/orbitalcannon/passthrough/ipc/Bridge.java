package com.orbitalcannon.passthrough.ipc;

import static com.orbitalcannon.passthrough.ipc.Protocol.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minecraft's end of the channel: the mapping, the ring A writer, the ring B reader, the seqlock slots, and the
 * heartbeat / epoch handshake with the GTA process.
 *
 * <p><b>Threading.</b> Everything that writes (ring A, heartbeat, McState) and everything that consumes ring B must
 * run on the Minecraft server thread: that is what makes the rings single-producer / single-consumer. The only other
 * thread is {@link PeerWatcher}, which merely peeks at cursors.
 */
public final class Bridge implements AutoCloseable {
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle(); // (segment, byteOffset)
	private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();

	/** GTA is considered gone after this long without a heartbeat. */
	private long gtaTimeoutNanos = 2_000_000_000L;

	private final SharedMemory shm;
	private final MemorySegment seg;
	private final RingWriter ringA;
	private final RingReader ringB;
	private final PlayerState.Reader playerReader;
	private final long mcEpoch;

	private long lastGtaHeartbeat;
	private long lastGtaBeatNanos;
	private long gtaEpochSeen;
	private boolean gtaAlive;
	private boolean gtaRestarted;

	private long recordsOut;
	private long recordsIn;

	private Bridge(SharedMemory shm) {
		this.shm = shm;
		this.seg = shm.segment();
		initOrValidate(seg);
		this.ringA = new RingWriter(seg, RING_A_CTL_OFF, RING_A_OFF);
		this.ringB = new RingReader(seg, RING_B_CTL_OFF, RING_B_OFF);
		this.playerReader = new PlayerState.Reader(seg, GTA_STATE_OFF);
		ringB.skipAll(); // leftovers of a dead GTA session; done before announcing ourselves so a live HELLO cannot be lost
		long epoch;
		do {
			epoch = ThreadLocalRandom.current().nextLong();
		} while (epoch == 0);
		this.mcEpoch = epoch;
		LONG.setRelease(seg, H_MC_HEARTBEAT, 0L);
		LONG.setRelease(seg, H_MC_EPOCH, epoch); // published last: the peer resyncs when it sees a new epoch
		this.gtaEpochSeen = (long) LONG.getAcquire(seg, H_GTA_EPOCH);
		this.lastGtaHeartbeat = (long) LONG.getAcquire(seg, H_GTA_HEARTBEAT);
	}

	public static Bridge open(String name, SharedMemory.Backend backend) throws IOException {
		ProtocolLayouts.verifyConstants();
		SharedMemory shm = SharedMemory.open(name, backend);
		try {
			return new Bridge(shm);
		} catch (RuntimeException e) {
			shm.close();
			throw e;
		}
	}

	/** Race-free formatting: whichever process arrives first initialises the region, the other waits for it. */
	static void initOrValidate(MemorySegment seg) {
		if (INT.compareAndSet(seg, H_INIT_STATE, INIT_RAW, INIT_BUSY)) {
			seg.set(JAVA_INT_UNALIGNED, H_MAGIC, MAGIC);
			seg.set(JAVA_INT_UNALIGNED, H_VERSION, VERSION);
			seg.set(JAVA_INT_UNALIGNED, H_TOTAL_SIZE, (int) TOTAL_SIZE);
			seg.set(JAVA_INT_UNALIGNED, H_RING_A_CAP, (int) RING_A_CAP);
			seg.set(JAVA_INT_UNALIGNED, H_RING_B_CAP, (int) RING_B_CAP);
			seg.set(JAVA_INT_UNALIGNED, RING_A_CTL_OFF + R_CAPACITY, (int) RING_A_CAP);
			seg.set(JAVA_INT_UNALIGNED, RING_A_CTL_OFF + R_MASK, (int) (RING_A_CAP - 1));
			seg.set(JAVA_INT_UNALIGNED, RING_B_CTL_OFF + R_CAPACITY, (int) RING_B_CAP);
			seg.set(JAVA_INT_UNALIGNED, RING_B_CTL_OFF + R_MASK, (int) (RING_B_CAP - 1));
			INT.setRelease(seg, H_INIT_STATE, INIT_READY);
		} else {
			long deadline = System.nanoTime() + 2_000_000_000L;
			while ((int) INT.getAcquire(seg, H_INIT_STATE) != INIT_READY) {
				if (System.nanoTime() > deadline) throw new IllegalStateException("timed out waiting for the peer to initialise shared memory");
				Thread.onSpinWait();
			}
		}
		if (seg.get(JAVA_INT_UNALIGNED, H_MAGIC) != MAGIC) throw new IllegalStateException("shared memory has the wrong magic: not an orbital passthrough region");
		if (seg.get(JAVA_INT_UNALIGNED, H_VERSION) != VERSION) {
			throw new IllegalStateException("protocol version mismatch: region is v" + seg.get(JAVA_INT_UNALIGNED, H_VERSION) + ", this build speaks v" + VERSION);
		}
		if (seg.get(JAVA_INT_UNALIGNED, H_TOTAL_SIZE) != (int) TOTAL_SIZE) throw new IllegalStateException("shared memory size mismatch");
	}

	// ---- liveness -------------------------------------------------------------------------------------------------

	/** Per server tick, before anything else: bumps our heartbeat and refreshes what we know about the GTA side. */
	public void beginTick() {
		LONG.setRelease(seg, H_MC_HEARTBEAT, (long) LONG.getAcquire(seg, H_MC_HEARTBEAT) + 1);
		refreshLiveness();
	}

	/** Re-reads the peer's epoch and heartbeat. Cheap; also called when events arrive between ticks. */
	public void refreshLiveness() {
		long now = System.nanoTime();
		long epoch = (long) LONG.getAcquire(seg, H_GTA_EPOCH);
		if (epoch != gtaEpochSeen) {
			gtaEpochSeen = epoch;
			if (epoch != 0) gtaRestarted = true; // a new GTA process: it will say HELLO and expect a full resync
			lastGtaBeatNanos = now;
		}
		long hb = (long) LONG.getAcquire(seg, H_GTA_HEARTBEAT);
		if (hb != lastGtaHeartbeat) {
			lastGtaHeartbeat = hb;
			lastGtaBeatNanos = now;
		}
		gtaAlive = gtaEpochSeen != 0 && now - lastGtaBeatNanos < gtaTimeoutNanos;
	}

	public boolean gtaAlive() {
		return gtaAlive;
	}

	// ---- presence, independent of server ticks ---------------------------------------------------------------------
	// A paused dedicated server stops running tick hooks, so liveness computed there can never wake it again. The
	// watcher thread keeps this flag current instead; DedicatedServerMixin reads it to un-pause the server.

	private volatile boolean gtaPresent;
	private long presenceHeartbeat;
	private long presenceBeatNanos;

	/** Watcher thread only. Two loads and a compare; safe to call in a tight loop. */
	public void pollPresence() {
		long epoch = (long) LONG.getAcquire(seg, H_GTA_EPOCH);
		long hb = (long) LONG.getAcquire(seg, H_GTA_HEARTBEAT);
		long now = System.nanoTime();
		if (hb != presenceHeartbeat) {
			presenceHeartbeat = hb;
			presenceBeatNanos = now;
		}
		gtaPresent = epoch != 0 && now - presenceBeatNanos < gtaTimeoutNanos;
	}

	/** True while a GTA process is attached and its frame loop is running. Readable from any thread. */
	public boolean gtaPresent() {
		return gtaPresent;
	}

	/** Test hook: shortens the heartbeat timeout so liveness loss can be exercised quickly. */
	void setGtaTimeoutNanos(long nanos) {
		this.gtaTimeoutNanos = nanos;
	}

	/** True exactly once after the GTA process changed. */
	public boolean consumeGtaRestarted() {
		boolean r = gtaRestarted;
		gtaRestarted = false;
		return r;
	}

	public long mcEpoch() {
		return mcEpoch;
	}

	// ---- GTA -> MC -------------------------------------------------------------------------------------------------

	public int drainEvents(RingReader.Handler handler, int max) {
		int n = ringB.drain(handler, max);
		recordsIn += n;
		return n;
	}

	/** Cheap cross-thread peek used by the wake-up watcher. */
	public boolean hasPendingEvents() {
		return ringB.available() > 0;
	}

	public boolean awaitPeerSignal(long timeoutMicros) {
		return shm.awaitSignal(timeoutMicros);
	}

	public boolean hasPeerSignal() {
		return shm.hasSignal();
	}

	public boolean readPlayer(PlayerState out) {
		return playerReader.read(out);
	}

	// ---- MC -> GTA -------------------------------------------------------------------------------------------------

	public RingWriter ringA() {
		return ringA;
	}

	public MemorySegment segment() {
		return seg;
	}

	public void commit() {
		ringA.commit();
	}

	public boolean sendSessionStart(int serial, double ax, double ay, double az, int minY, int maxY) {
		long p = ringA.begin(MSG_SESSION_START, SS_SIZE);
		if (p < 0) return false;
		seg.set(JAVA_LONG_UNALIGNED, p + SS_MC_EPOCH, mcEpoch);
		seg.set(JAVA_INT_UNALIGNED, p + SS_SYNC_SERIAL, serial);
		seg.set(JAVA_INT_UNALIGNED, p + SS_FLAGS, 0);
		seg.set(JAVA_DOUBLE_UNALIGNED, p + SS_ANCHOR_X, ax);
		seg.set(JAVA_DOUBLE_UNALIGNED, p + SS_ANCHOR_Y, ay);
		seg.set(JAVA_DOUBLE_UNALIGNED, p + SS_ANCHOR_Z, az);
		seg.set(JAVA_INT_UNALIGNED, p + SS_MIN_Y, minY);
		seg.set(JAVA_INT_UNALIGNED, p + SS_MAX_Y, maxY);
		recordsOut++;
		return true;
	}

	public boolean sendPalette(int id, int flags, int rgb, String name) {
		byte[] nb = name.getBytes(StandardCharsets.UTF_8);
		if (nb.length > 255) nb = java.util.Arrays.copyOf(nb, 255);
		long p = ringA.begin(MSG_PALETTE_ENTRY, PE_HDR_SIZE + nb.length);
		if (p < 0) return false;
		seg.set(JAVA_SHORT_UNALIGNED, p + PE_ID, (short) id);
		seg.set(JAVA_SHORT_UNALIGNED, p + PE_FLAGS, (short) flags);
		seg.set(JAVA_INT_UNALIGNED, p + PE_RGB, rgb);
		seg.set(JAVA_SHORT_UNALIGNED, p + PE_NAME_LEN, (short) nb.length);
		seg.set(JAVA_SHORT_UNALIGNED, p + 10, (short) 0);
		MemorySegment.copy(nb, 0, seg, JAVA_BYTE, p + PE_HDR_SIZE, nb.length);
		recordsOut++;
		return true;
	}

	public boolean sendSectionUnload(int sx, int sy, int sz) {
		long p = ringA.begin(MSG_SECTION_UNLOAD, SU_SIZE);
		if (p < 0) return false;
		seg.set(JAVA_INT_UNALIGNED, p, sx);
		seg.set(JAVA_INT_UNALIGNED, p + 4, sy);
		seg.set(JAVA_INT_UNALIGNED, p + 8, sz);
		recordsOut++;
		return true;
	}

	public boolean sendAck(int seq, int result, int x, int y, int z, float progress) {
		long p = ringA.begin(MSG_ACK, ACK_SIZE);
		if (p < 0) return false;
		seg.set(JAVA_INT_UNALIGNED, p + ACK_SEQ, seq);
		seg.set(JAVA_SHORT_UNALIGNED, p + ACK_RESULT, (short) result);
		seg.set(JAVA_SHORT_UNALIGNED, p + ACK_REASON, (short) 0);
		seg.set(JAVA_INT_UNALIGNED, p + ACK_X, x);
		seg.set(JAVA_INT_UNALIGNED, p + ACK_Y, y);
		seg.set(JAVA_INT_UNALIGNED, p + ACK_Z, z);
		seg.set(JAVA_FLOAT_UNALIGNED, p + ACK_PROGRESS, progress);
		recordsOut++;
		return true;
	}

	public boolean sendPing(long token) {
		return sendToken(MSG_PING, token);
	}

	public boolean sendPong(long token) {
		return sendToken(MSG_PONG, token);
	}

	private boolean sendToken(int type, long token) {
		long p = ringA.begin(type, PING_SIZE);
		if (p < 0) return false;
		seg.set(JAVA_LONG_UNALIGNED, p, token);
		recordsOut++;
		return true;
	}

	/** Publishes the latest Minecraft-side stats through the MC -> GTA seqlock slot. */
	public void publishMcState(long tick, long dayTime, float mspt, int flags, int sectionsStreamed, int sectionsPending) {
		long slot = MC_STATE_OFF;
		int seq = (int) INT.getAcquire(seg, slot);
		INT.setRelease(seg, slot, seq + 1);
		VarHandle.releaseFence();
		long b = slot + SLOT_DATA;
		seg.set(JAVA_LONG_UNALIGNED, b + MS_TICK, tick);
		seg.set(JAVA_LONG_UNALIGNED, b + MS_DAY_TIME, dayTime);
		seg.set(JAVA_FLOAT_UNALIGNED, b + MS_MSPT, mspt);
		seg.set(JAVA_INT_UNALIGNED, b + MS_FLAGS, flags);
		seg.set(JAVA_INT_UNALIGNED, b + MS_SECTIONS_STREAMED, sectionsStreamed);
		seg.set(JAVA_INT_UNALIGNED, b + MS_SECTIONS_PENDING, sectionsPending);
		INT.setRelease(seg, slot, seq + 2);
	}

	public long recordsOut() {
		return recordsOut;
	}

	public long recordsIn() {
		return recordsIn;
	}

	public long corruptRecords() {
		return ringB.corruptCount();
	}

	public long droppedOut() {
		return ringA.droppedCount();
	}

	public String describe() {
		return shm.describe();
	}

	@Override
	public void close() {
		try {
			LONG.setRelease(seg, H_MC_EPOCH, 0L); // tells the GTA side we are gone
		} catch (RuntimeException ignored) {
			// mapping already unmapped
		}
		shm.close();
	}
}
