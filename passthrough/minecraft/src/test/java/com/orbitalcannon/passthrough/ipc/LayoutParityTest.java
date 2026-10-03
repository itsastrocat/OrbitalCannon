package com.orbitalcannon.passthrough.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Diffs the Java mirror against the facts the C++ compiler printed for orbital_ipc.h (see tests/layout_dump.cpp).
 * Skipped when OCP_LAYOUT_DUMP is unset; passthrough/tools/run_all.sh always sets it.
 */
class LayoutParityTest {
	private static Map<String, Long> cppFacts() throws Exception {
		String path = System.getenv("OCP_LAYOUT_DUMP");
		Assumptions.assumeTrue(path != null && !path.isBlank(), "OCP_LAYOUT_DUMP not set: run passthrough/tools/run_all.sh");
		Map<String, Long> facts = new LinkedHashMap<>();
		for (String line : Files.readAllLines(Path.of(path))) {
			String[] kv = line.trim().split("\\s+");
			if (kv.length == 2) facts.put(kv[0], Long.parseLong(kv[1]));
		}
		return facts;
	}

	@Test
	void everyStructSizeAndFieldOffsetMatchesTheCppCompiler() throws Exception {
		Map<String, Long> cpp = cppFacts();
		Map<String, Long> java = ProtocolLayouts.fieldOffsets();
		TreeSet<String> problems = new TreeSet<>();
		for (var e : java.entrySet()) {
			Long c = cpp.get(e.getKey());
			if (c == null) problems.add(e.getKey() + ": in Java but not in C++");
			else if (!c.equals(e.getValue())) problems.add(e.getKey() + ": Java " + e.getValue() + " != C++ " + c);
		}
		for (var e : cpp.entrySet()) {
			if (e.getKey().startsWith("C.") || e.getKey().equals("SeqSlot.data")) continue;
			if (!java.containsKey(e.getKey())) problems.add(e.getKey() + ": in C++ but not in Java");
		}
		assertTrue(problems.isEmpty(), "layout drift:\n  " + String.join("\n  ", problems));
		assertTrue(java.size() > 100, "suspiciously few facts compared: " + java.size());
		assertEquals(Protocol.SLOT_DATA, (long) cpp.get("SeqSlot.data"));
	}

	@Test
	void everyConstantAndEnumValueMatches() throws Exception {
		Map<String, Long> cpp = cppFacts();
		Map<String, Long> j = new LinkedHashMap<>();
		j.put("kMagic", (long) Protocol.MAGIC);
		j.put("kVersion", (long) Protocol.VERSION);
		j.put("kHeaderOff", Protocol.HEADER_OFF);
		j.put("kGtaStateOff", Protocol.GTA_STATE_OFF);
		j.put("kMcStateOff", Protocol.MC_STATE_OFF);
		j.put("kRingACtlOff", Protocol.RING_A_CTL_OFF);
		j.put("kRingBCtlOff", Protocol.RING_B_CTL_OFF);
		j.put("kRingAOff", Protocol.RING_A_OFF);
		j.put("kRingACap", Protocol.RING_A_CAP);
		j.put("kRingBOff", Protocol.RING_B_OFF);
		j.put("kRingBCap", Protocol.RING_B_CAP);
		j.put("kTotalSize", Protocol.TOTAL_SIZE);
		j.put("kMaxPayload", (long) Protocol.MAX_PAYLOAD);
		j.put("kMaxEntriesPerSectionMsg", (long) Protocol.MAX_ENTRIES_PER_SECTION_MSG);
		j.put("kMaxUpdatesPerMsg", (long) Protocol.MAX_UPDATES_PER_MSG);
		j.put("kInitRaw", (long) Protocol.INIT_RAW);
		j.put("kInitBusy", (long) Protocol.INIT_BUSY);
		j.put("kInitReady", (long) Protocol.INIT_READY);
		j.put("kMsgPad", (long) Protocol.MSG_PAD);
		j.put("kMsgPing", (long) Protocol.MSG_PING);
		j.put("kMsgPong", (long) Protocol.MSG_PONG);
		j.put("kMsgSessionStart", (long) Protocol.MSG_SESSION_START);
		j.put("kMsgPaletteEntry", (long) Protocol.MSG_PALETTE_ENTRY);
		j.put("kMsgSectionData", (long) Protocol.MSG_SECTION_DATA);
		j.put("kMsgSectionUnload", (long) Protocol.MSG_SECTION_UNLOAD);
		j.put("kMsgVoxelUpdates", (long) Protocol.MSG_VOXEL_UPDATES);
		j.put("kMsgAck", (long) Protocol.MSG_ACK);
		j.put("kMsgHello", (long) Protocol.MSG_HELLO);
		j.put("kMsgAnchor", (long) Protocol.MSG_ANCHOR);
		j.put("kMsgBreakRequest", (long) Protocol.MSG_BREAK_REQUEST);
		j.put("kMsgPlaceRequest", (long) Protocol.MSG_PLACE_REQUEST);
		j.put("kMsgCollision", (long) Protocol.MSG_COLLISION);
		j.put("kPalOpaque", (long) Protocol.PAL_OPAQUE);
		j.put("kPalPlaceable", (long) Protocol.PAL_PLACEABLE);
		j.put("kPalTranslucent", (long) Protocol.PAL_TRANSLUCENT);
		j.put("kSecReplace", (long) Protocol.SEC_REPLACE);
		j.put("kSecLast", (long) Protocol.SEC_LAST);
		j.put("kUpdFromGta", (long) Protocol.UPD_FROM_GTA);
		j.put("kAckBroken", (long) Protocol.ACK_BROKEN);
		j.put("kAckDamaged", (long) Protocol.ACK_DAMAGED);
		j.put("kAckPlaced", (long) Protocol.ACK_PLACED);
		j.put("kAckDeniedReach", (long) Protocol.ACK_DENIED_REACH);
		j.put("kAckDeniedUnloaded", (long) Protocol.ACK_DENIED_UNLOADED);
		j.put("kAckDeniedUnbreakable", (long) Protocol.ACK_DENIED_UNBREAKABLE);
		j.put("kAckDeniedOccupied", (long) Protocol.ACK_DENIED_OCCUPIED);
		j.put("kAckDeniedAir", (long) Protocol.ACK_DENIED_AIR);
		j.put("kAckDeniedHitbox", (long) Protocol.ACK_DENIED_HITBOX);
		j.put("kAckDeniedRate", (long) Protocol.ACK_DENIED_RATE);
		j.put("kAckDeniedHeight", (long) Protocol.ACK_DENIED_HEIGHT);
		j.put("kAckDeniedNoAnchor", (long) Protocol.ACK_DENIED_NO_ANCHOR);
		j.put("kAckDeniedBadPalette", (long) Protocol.ACK_DENIED_BAD_PALETTE);
		j.put("kDown", (long) Protocol.FACE_DOWN);
		j.put("kUp", (long) Protocol.FACE_UP);
		j.put("kNorth", (long) Protocol.FACE_NORTH);
		j.put("kSouth", (long) Protocol.FACE_SOUTH);
		j.put("kWest", (long) Protocol.FACE_WEST);
		j.put("kEast", (long) Protocol.FACE_EAST);
		j.put("kSrcBullet", (long) Protocol.SRC_BULLET);
		j.put("kSrcMelee", (long) Protocol.SRC_MELEE);
		j.put("kSrcExplosive", (long) Protocol.SRC_EXPLOSIVE);
		j.put("kSrcTool", (long) Protocol.SRC_TOOL);
		j.put("kSrcVehicle", (long) Protocol.SRC_VEHICLE);
		j.put("kColPed", (long) Protocol.COL_PED);
		j.put("kColVehicle", (long) Protocol.COL_VEHICLE);
		j.put("kPfOnFoot", (long) Protocol.PF_ON_FOOT);
		j.put("kPfInVehicle", (long) Protocol.PF_IN_VEHICLE);
		j.put("kPfShooting", (long) Protocol.PF_SHOOTING);
		j.put("kPfDead", (long) Protocol.PF_DEAD);
		j.put("kPfRagdoll", (long) Protocol.PF_RAGDOLL);
		j.put("kPfAnchored", (long) Protocol.PF_ANCHORED);

		TreeSet<String> problems = new TreeSet<>();
		for (var e : j.entrySet()) {
			Long c = cpp.get("C." + e.getKey());
			if (c == null) problems.add(e.getKey() + " missing from the C++ dump");
			else if (!c.equals(e.getValue())) problems.add(e.getKey() + ": Java " + e.getValue() + " != C++ " + c);
		}
		for (String k : cpp.keySet()) {
			if (k.startsWith("C.") && !j.containsKey(k.substring(2))) problems.add(k + " is in C++ but has no Java counterpart in this test");
		}
		assertTrue(problems.isEmpty(), "constant drift:\n  " + String.join("\n  ", problems));
		// Face normals follow Direction.get3DDataValue order; spot-check the two that GTA's mapping depends on most.
		assertEquals(-1, Protocol.FACE_NORMAL[Protocol.FACE_NORTH][2]);
		assertEquals(1, Protocol.FACE_NORMAL[Protocol.FACE_UP][1]);
	}

	@Test
	void layoutsSelfVerifyAgainstHandWrittenConstants() {
		ProtocolLayouts.verifyConstants(); // throws on any mismatch
	}
}
