# Orbital Passthrough IPC protocol v1

`orbital_ipc.h` is the single source of truth; `Protocol.java` mirrors it. A test (`LayoutParityTest`) diffs every struct size, field offset and enum value against what the C++ compiler reports, so the two cannot drift apart silently. This page is the human-readable summary.

All values are little-endian x86-64, every field is naturally aligned. Everything read from shared memory is treated as untrusted input.

## Region map (one 4.52 MiB mapping)

| Offset | Size | Contents |
|---|---|---|
| `0x0000` | 4 KiB | `SharedHeader`: magic `OCPT`, version, init state, epochs, heartbeats |
| `0x1000` | 4 KiB | seqlock slot, **GTA → MC**: `PlayerState` (128 B at +64) |
| `0x2000` | 4 KiB | seqlock slot, **MC → GTA**: `McState` (48 B at +64) |
| `0x3000` | 4 KiB | `RingControl` for ring A (MC → GTA) |
| `0x4000` | 4 KiB | `RingControl` for ring B (GTA → MC) |
| `0x5000` | 4 MiB | ring A data: bulk voxel streaming |
| `0x405000` | 512 KiB | ring B data: interaction events |

Windows: named pagefile-backed section `Local\OrbitalCannonPassthrough_v1` plus a named auto-reset event `…_gta2mc` that GTA sets after publishing so the Java reader wakes in microseconds. Elsewhere: a `MAP_SHARED` file at `/dev/shm/<name>` (used for CI and tests).

## Rings

Lock-free single-producer / single-consumer byte rings. Cursors (`head`, `tail`) are free-running 64-bit byte counters on separate cache lines; the producer publishes a batch with one **release** store of `head`, the consumer observes it with an **acquire** load and publishes consumption with a release store of `tail`. On x86-64 that is plain `MOV`s: no locked instruction, no syscall.

Records are `MsgHeader { u16 type; u16 flags; u32 len }` followed by `len` payload bytes padded to 8. A record never straddles the wrap point: the producer writes a `kMsgPad` record to the end of the ring instead. A full ring refuses whole records (never partial) and counts them in `RingControl.dropped`. The reader validates every length against the bytes actually available and resynchronises to `head` on anything malformed.

## Seqlocks ("latest value wins" state)

`PlayerState` (every GTA frame) and `McState` (every server tick) are not queued: a writer increments the slot's `seq` to an odd value, writes, then to the next even value; a reader retries until it sees the same even `seq` before and after copying. No growth, no backlog, no lost-update ambiguity.

## Messages

Ring A, Minecraft → GTA

| Type | Name | Payload |
|---|---|---|
| 1 | `SESSION_START` | `mc_epoch`, `sync_serial`, Minecraft anchor point (3 × f64), build height range. GTA drops all voxel state. |
| 2 | `PALETTE_ENTRY` | `id` (u16), flags (opaque / placeable / translucent), map colour `rgb`, registry name (e.g. `minecraft:stone`). Always precedes the first use of an id. |
| 3 | `SECTION_DATA` | section coords `sx,sy,sz`, count, flags (`REPLACE`, `LAST`), then `count` × `{u16 local, u16 id}`. Only **visible** voxels are sent. |
| 4 | `SECTION_UNLOAD` | `sx,sy,sz` |
| 5 | `VOXEL_UPDATES` | count, server tick, then `count` × `{i32 x,y,z; u16 id; u16 flags}`. `id == 0` clears. `FROM_GTA` marks results of GTA's own requests. |
| 6 | `ACK` | request seq, result code, the voxel, accumulated damage fraction |
| 7/8 | `PING`/`PONG` | u64 token, echoed unchanged (either side may ping) |

Ring B, GTA → Minecraft

| Type | Name | Payload |
|---|---|---|
| 16 | `HELLO` | `gta_epoch`, protocol version, requested view radii, prop budget. Triggers a full resync. |
| 17 | `ANCHOR` | the GTA world point (3 × f64) that the Minecraft anchor from `SESSION_START` is pinned to |
| 18 | `BREAK_REQUEST` | seq, source (bullet / melee / explosive / tool / vehicle), face, voxel, damage, weapon hash |
| 19 | `PLACE_REQUEST` | seq, face and voxel that were clicked, palette id to place |
| 20 | `COLLISION` | kind (ped / vehicle), contact voxel, contact normal, speed, speed lost in the frame, mass |

Voxel `local` index = `x | z << 4 | y << 8`. Faces use Minecraft's `Direction.get3DDataValue()` order: down, up, north, south, west, east.

## Session lifecycle

1. Each process publishes a random non-zero **epoch** in the header at start and increments a **heartbeat** every tick/frame; a peer that sees the epoch change knows the other side restarted, and one whose heartbeat stalls for 2 s is considered gone. Epoch 0 means "cleanly detached".
2. GTA sends `HELLO`. Minecraft answers with `SESSION_START` (its anchor point) and waits.
3. The player pins the world: GTA sends `ANCHOR` (its own position) and both sides can now map coordinates. Minecraft starts streaming sections around the player: `PALETTE_ENTRY`, then `SECTION_DATA`.
4. From then on: `VOXEL_UPDATES` for every block change, `ACK`s for requests, and the two seqlock slots.
5. If GTA restarts it re-sends `HELLO`, is given a fresh session, and re-anchors at the same GTA point. Minecraft remembers every edit because it owns the world.

The anchor point is the world spawn position (or `world.anchorX/Y/Z`), never derived from terrain, so editing the terrain cannot shift the mirrored world between sessions.

## Coordinates

Minecraft: +X east, +Y up, +Z south. GTA: +X east, +Y north, +Z up. `(x, y, z)_mc → (x, −z, y)_gta` is a rotation (determinant +1): never mirrored. One block = one metre. The anchor pair fixes the translation:

```
gta = anchor_gta + ( mc.x − amc.x,  −(mc.z − amc.z),  mc.y − amc.y )
```

## Versioning

`kVersion` is checked when attaching; a mismatch is refused with a clear error instead of misreading memory. A reader ignores record types it does not know, so new message types can be added without a version bump; changing an existing layout requires one.
