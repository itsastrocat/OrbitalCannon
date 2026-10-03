package com.orbitalcannon.passthrough.ipc;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Minecraft-side Bridge against a hand-driven "GTA" played through a second mapping of the same file. */
class BridgeTest {
	private static final java.lang.invoke.VarHandle LONG = java.lang.foreign.ValueLayout.JAVA_LONG.varHandle();

	private String name;
	private Bridge mc;
	private SharedMemory gtaShm;
	private MemorySegment gta;
	private RingReader gtaReadsA;
	private RingWriter gtaWritesB;

	@BeforeEach
	void open() throws IOException {
		name = "ocp_java_test_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
		mc = Bridge.open(name, SharedMemory.Backend.FILE);
		gtaShm = SharedMemory.open(name, SharedMemory.Backend.FILE);
		gta = gtaShm.segment();
		gtaReadsA = new RingReader(gta, Protocol.RING_A_CTL_OFF, Protocol.RING_A_OFF);
		gtaWritesB = new RingWriter(gta, Protocol.RING_B_CTL_OFF, Protocol.RING_B_OFF);
	}

	@AfterEach
	void close() throws IOException {
		mc.close();
		gtaShm.close();
		Files.deleteIfExists(FileBackedMemory.resolve(name));
	}

	private void gtaAttach(long epoch) {
		LONG.setRelease(gta, Protocol.H_GTA_EPOCH, epoch);
	}

	private void gtaBeat() {
		LONG.setRelease(gta, Protocol.H_GTA_HEARTBEAT, (long) LONG.getAcquire(gta, Protocol.H_GTA_HEARTBEAT) + 1);
	}

	private record Msg(int type, byte[] bytes) {
		int i32(int off) {
			return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(off);
		}

		long i64(int off) {
			return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(off);
		}

		int u16(int off) {
			return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(off) & 0xFFFF;
		}

		float f32(int off) {
			return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(off);
		}

		double f64(int off) {
			return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble(off);
		}
	}

	private List<Msg> gtaDrain() {
		List<Msg> out = new ArrayList<>();
		gtaReadsA.drain((type, seg, off, len) -> out.add(new Msg(type, seg.asSlice(off, len).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))), Integer.MAX_VALUE);
		return out;
	}

	@Test
	void headerIsFormattedAndEpochPublished() {
		assertEquals(Protocol.MAGIC, gta.get(JAVA_INT_UNALIGNED, Protocol.H_MAGIC));
		assertEquals(Protocol.VERSION, gta.get(JAVA_INT_UNALIGNED, Protocol.H_VERSION));
		assertEquals(Protocol.INIT_READY, gta.get(JAVA_INT_UNALIGNED, Protocol.H_INIT_STATE));
		assertNotEquals(0L, (long) LONG.getAcquire(gta, Protocol.H_MC_EPOCH));
		assertEquals(mc.mcEpoch(), (long) LONG.getAcquire(gta, Protocol.H_MC_EPOCH));
		mc.beginTick();
		mc.beginTick();
		assertEquals(2L, (long) LONG.getAcquire(gta, Protocol.H_MC_HEARTBEAT));
	}

	@Test
	void rejectsForeignMemoryAndWrongVersion() throws Exception {
		String other = name + "_bad";
		Path p = FileBackedMemory.resolve(other);
		try {
			try (SharedMemory shm = SharedMemory.open(other, SharedMemory.Backend.FILE)) {
				shm.segment().set(JAVA_INT_UNALIGNED, Protocol.H_INIT_STATE, Protocol.INIT_READY);
				shm.segment().set(JAVA_INT_UNALIGNED, Protocol.H_MAGIC, 0x12345678);
				assertThrows(IllegalStateException.class, () -> Bridge.open(other, SharedMemory.Backend.FILE).close());
				shm.segment().set(JAVA_INT_UNALIGNED, Protocol.H_MAGIC, Protocol.MAGIC);
				shm.segment().set(JAVA_INT_UNALIGNED, Protocol.H_VERSION, 99);
				var ex = assertThrows(IllegalStateException.class, () -> Bridge.open(other, SharedMemory.Backend.FILE).close());
				assertTrue(ex.getMessage().contains("version"), ex.getMessage());
			}
		} finally {
			Files.deleteIfExists(p);
		}
	}

	@Test
	void detectsGtaAttachRestartAndHeartbeatTimeout() throws Exception {
		mc.beginTick();
		assertFalse(mc.gtaAlive());
		gtaAttach(0xAAAAL);
		gtaBeat();
		mc.beginTick();
		assertTrue(mc.gtaAlive());
		assertTrue(mc.consumeGtaRestarted(), "first sight of a GTA epoch counts as a (re)start");
		assertFalse(mc.consumeGtaRestarted(), "reported exactly once");
		// A new GTA process: new epoch even though heartbeats continue.
		gtaAttach(0xBBBBL);
		mc.beginTick();
		assertTrue(mc.consumeGtaRestarted());
		// Heartbeat stops: considered gone after the timeout.
		mc.setGtaTimeoutNanos(50_000_000L);
		gtaBeat();
		mc.beginTick();
		assertTrue(mc.gtaAlive());
		Thread.sleep(80);
		mc.beginTick();
		assertFalse(mc.gtaAlive());
		gtaBeat();
		mc.beginTick();
		assertTrue(mc.gtaAlive(), "recovers as soon as heartbeats resume");
		// Clean detach (epoch zeroed by the GTA side).
		gtaAttach(0);
		mc.beginTick();
		assertFalse(mc.gtaAlive());
	}

	@Test
	void everyMinecraftToGtaMessageDecodesToExactlyWhatWasSent() {
		assertTrue(mc.sendSessionStart(7, 0.5, 64.25, -3.5, -64, 319));
		assertTrue(mc.sendPalette(300, Protocol.PAL_OPAQUE | Protocol.PAL_PLACEABLE, 0x7F7F7F, "minecraft:stone"));
		assertTrue(mc.sendSectionUnload(-2, 4, 9));
		assertTrue(mc.sendAck(55, Protocol.ACK_BROKEN, 1, -2, 3, 0.75f));
		assertTrue(mc.sendPing(123456789L));
		assertTrue(mc.sendPong(987654321L));
		mc.commit();
		List<Msg> got = gtaDrain();
		assertEquals(6, got.size());

		Msg ss = got.get(0);
		assertEquals(Protocol.MSG_SESSION_START, ss.type());
		assertEquals(mc.mcEpoch(), ss.i64(Protocol.SS_MC_EPOCH));
		assertEquals(7, ss.i32(Protocol.SS_SYNC_SERIAL));
		assertEquals(0.5, ss.f64(Protocol.SS_ANCHOR_X));
		assertEquals(64.25, ss.f64(Protocol.SS_ANCHOR_Y));
		assertEquals(-3.5, ss.f64(Protocol.SS_ANCHOR_Z));
		assertEquals(-64, ss.i32(Protocol.SS_MIN_Y));
		assertEquals(319, ss.i32(Protocol.SS_MAX_Y));

		Msg pe = got.get(1);
		assertEquals(Protocol.MSG_PALETTE_ENTRY, pe.type());
		assertEquals(300, pe.u16(Protocol.PE_ID));
		assertEquals(Protocol.PAL_OPAQUE | Protocol.PAL_PLACEABLE, pe.u16(Protocol.PE_FLAGS));
		assertEquals(0x7F7F7F, pe.i32(Protocol.PE_RGB));
		int nameLen = pe.u16(Protocol.PE_NAME_LEN);
		assertEquals("minecraft:stone", new String(pe.bytes(), Protocol.PE_HDR_SIZE, nameLen, java.nio.charset.StandardCharsets.UTF_8));

		assertEquals(Protocol.MSG_SECTION_UNLOAD, got.get(2).type());
		assertEquals(-2, got.get(2).i32(0));
		assertEquals(9, got.get(2).i32(8));
		Msg ack = got.get(3);
		assertEquals(Protocol.MSG_ACK, ack.type());
		assertEquals(55, ack.i32(Protocol.ACK_SEQ));
		assertEquals(Protocol.ACK_BROKEN, ack.u16(Protocol.ACK_RESULT));
		assertEquals(-2, ack.i32(Protocol.ACK_Y));
		assertEquals(0.75f, ack.f32(Protocol.ACK_PROGRESS));
		assertEquals(123456789L, got.get(4).i64(0));
		assertEquals(Protocol.MSG_PONG, got.get(5).type());
	}

	@Test
	void minecraftSideSeesEverythingGtaSendsAndEachWireFieldLandsInTheRightPlace() {
		// Craft GTA's messages byte for byte at the offsets the C++ header documents.
		long p = gtaWritesB.begin(Protocol.MSG_BREAK_REQUEST, Protocol.BR_SIZE);
		gta.set(JAVA_INT_UNALIGNED, p + Protocol.BR_SEQ, 41);
		gta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, p + Protocol.BR_SOURCE, (byte) Protocol.SRC_BULLET);
		gta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, p + Protocol.BR_FACE, (byte) Protocol.FACE_UP);
		gta.set(JAVA_INT_UNALIGNED, p + Protocol.BR_X, -5);
		gta.set(JAVA_INT_UNALIGNED, p + Protocol.BR_Y, 70);
		gta.set(JAVA_INT_UNALIGNED, p + Protocol.BR_Z, 12);
		gta.set(JAVA_FLOAT_UNALIGNED, p + Protocol.BR_DAMAGE, 26.5f);
		gta.set(JAVA_INT_UNALIGNED, p + Protocol.BR_WEAPON, 0x1B06D571);
		long q = gtaWritesB.begin(Protocol.MSG_PLACE_REQUEST, Protocol.PR_SIZE);
		gta.set(JAVA_INT_UNALIGNED, q + Protocol.PR_SEQ, 42);
		gta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, q + Protocol.PR_FACE, (byte) Protocol.FACE_NORTH);
		gta.set(JAVA_SHORT_UNALIGNED, q + Protocol.PR_PALETTE_ID, (short) 777);
		gta.set(JAVA_INT_UNALIGNED, q + Protocol.PR_X, 1);
		gtaWritesB.commit();
		assertTrue(mc.hasPendingEvents());

		List<String> seen = new ArrayList<>();
		mc.drainEvents((type, seg, off, len) -> {
			if (type == Protocol.MSG_BREAK_REQUEST) {
				assertEquals(Protocol.BR_SIZE, len);
				seen.add("break " + seg.get(JAVA_INT_UNALIGNED, off + Protocol.BR_SEQ) + " " + seg.get(JAVA_INT_UNALIGNED, off + Protocol.BR_X) + ","
					+ seg.get(JAVA_INT_UNALIGNED, off + Protocol.BR_Y) + "," + seg.get(JAVA_INT_UNALIGNED, off + Protocol.BR_Z) + " dmg "
					+ seg.get(JAVA_FLOAT_UNALIGNED, off + Protocol.BR_DAMAGE) + " w " + Integer.toHexString(seg.get(JAVA_INT_UNALIGNED, off + Protocol.BR_WEAPON)));
			} else if (type == Protocol.MSG_PLACE_REQUEST) {
				seen.add("place " + seg.get(JAVA_INT_UNALIGNED, off + Protocol.PR_SEQ) + " pal " + (seg.get(JAVA_SHORT_UNALIGNED, off + Protocol.PR_PALETTE_ID) & 0xFFFF));
			}
		}, 10);
		assertEquals(List.of("break 41 -5,70,12 dmg 26.5 w 1b06d571", "place 42 pal 777"), seen);
		assertFalse(mc.hasPendingEvents());
	}

	@Test
	void playerStateAndMcStateTravelThroughTheirSlots() {
		PlayerState.Writer w = new PlayerState.Writer(gta, Protocol.GTA_STATE_OFF);
		PlayerState s = new PlayerState();
		s.frame = 99;
		s.px = 1000.25;
		s.py = 2000.5;
		s.pz = 30.75;
		s.vx = 1;
		s.vy = 2;
		s.vz = 3;
		s.heading = 90;
		s.ex = 10;
		s.ey = 20;
		s.ez = 30;
		s.ax = 0;
		s.ay = 1;
		s.az = 0;
		s.hitMin[0] = -0.35f;
		s.hitMax[2] = 1.8f;
		s.flags = Protocol.PF_ON_FOOT | Protocol.PF_ANCHORED;
		s.weaponHash = 0xABCDEF;
		s.vehicleSpeed = 12.5f;
		PlayerState got = new PlayerState();
		assertFalse(mc.readPlayer(got), "nothing written yet");
		w.write(s);
		assertTrue(mc.readPlayer(got));
		assertEquals(99, got.frame);
		assertEquals(1000.25, got.px);
		assertEquals(30.75, got.pz);
		assertEquals(2f, got.vy);
		assertEquals(-0.35f, got.hitMin[0]);
		assertEquals(1.8f, got.hitMax[2]);
		assertTrue(got.onFoot());
		assertEquals(0xABCDEF, got.weaponHash);
		assertEquals(12.5f, got.vehicleSpeed);

		mc.publishMcState(1234, 6000, 4.5f, 3, 17, 5);
		long base = Protocol.MC_STATE_OFF + Protocol.SLOT_DATA;
		assertEquals(1234L, gta.get(JAVA_LONG_UNALIGNED, base + Protocol.MS_TICK));
		assertEquals(4.5f, gta.get(JAVA_FLOAT_UNALIGNED, base + Protocol.MS_MSPT));
		assertEquals(17, gta.get(JAVA_INT_UNALIGNED, base + Protocol.MS_SECTIONS_STREAMED));
		assertEquals(2, (int) java.lang.foreign.ValueLayout.JAVA_INT.varHandle().getAcquire(gta, Protocol.MC_STATE_OFF), "seqlock counter is even (stable) after one write");
	}

	@Test
	void fullRingRefusesRecordsWholesaleAndRecoversOnceDrained() {
		int sent = 0;
		while (mc.sendPing(sent)) sent++;
		assertTrue(sent > 1000, "ring A holds a lot of small records, got " + sent);
		assertTrue(mc.droppedOut() >= 1);
		mc.commit();
		List<Msg> got = gtaDrain();
		assertEquals(sent, got.size());
		for (int i = 0; i < got.size(); i++) assertEquals(i, got.get(i).i64(0));
		assertTrue(mc.sendPing(sent), "space is available again after the GTA side drained");
	}
}
