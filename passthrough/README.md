# Orbital Passthrough: Minecraft voxel engine, GTA V renderer

Run Minecraft and Grand Theft Auto V side by side and build, shoot and drive into Minecraft blocks inside the GTA V world.

```
   Minecraft 26.3 (Fabric, headless OK)                 GTA V (ScriptHookV .asi)
 ┌──────────────────────────────────────┐            ┌───────────────────────────────────────┐
 │ world state, physics rules, saving    │            │ renders voxels as 1 m props           │
 │ visible-shell extraction per section  │ ring A     │ prop pool under a hard entity budget  │
 │ block-change hook (mixin)             ├───────────►│ voxel mirror, nearest-first spawning  │
 │                                       │ voxels,    │                                       │
 │ applies break / place / impact        │ updates,   │ player hitbox, weapon impacts,        │
 │ (validated, rate limited, reach       │ ACKs       │ raycasts, vehicle collisions          │
 │  checked)                             │◄───────────┤                                       │
 └──────────────────────────────────────┘ ring B     └───────────────────────────────────────┘
        shared memory, lock-free SPSC rings + seqlock slots, no kernel call on the hot path
```

Minecraft is the **authority**: it owns the world, decides what breaks, saves it. GTA V is a **client**: it draws what it is told and reports what the player does. Closing GTA V loses nothing; reconnecting restreams the world with every edit intact.

## What is verified here, and what is not

I could build and run everything except GTA V itself (no Windows, no game in this environment). Be clear about which is which:

| Verified by running it | Result |
|---|---|
| GTA-side C++ unit tests: ring buffers (2M-message two-thread stress), seqlock, containers, transform, voxel store, prop pool/budgets, interaction, config | 23 + 17 + 6 tests pass, also under ASan + UBSan; TSan clean on the lock-free paths |
| ScriptHookV native layer against a scripted fake of the native dispatcher: **every native hash and argument count checked against the FiveM natives reference**, argument/Vector3 marshalling, camera and hitbox maths | 6 tests |
| Java: rings and seqlock, bridge handshake, scanner and streamer against a brute-force oracle on **real Minecraft 26.3 block states**, including random edits, mass edits, view movement, late-loaded chunks and ring back-pressure | 30 tests pass |
| Wire-format parity: every struct size, field offset and enum value diffed between the C++ compiler's output and the Java mirror | 3 tests, 218 facts |
| `OrbitalPassthrough.asi` cross-compiled to a real PE32+ x86-64 DLL (MinGW-w64, against an SDK stub) | builds, warning-free |
| **End to end:** a real headless Minecraft 26.3 dedicated server running the mod, and the real GTA-side code driven by a scripted mock GTA, over shared memory: handshake, anchoring, streaming, mining, block reveal, placing, bullets (damage accumulation), rockets (area destruction), out-of-reach refusal, vehicle impacts, GTA restart with state intact, server never pausing while GTA is attached | 25 phases pass; `tools/run_all.sh` |

| **Not** verified (needs Windows + GTA V + the SDK) | Why it matters |
|---|---|
| The ScriptHookV link against the real SDK, and loading in the game | The `.asi` links against a stub here. The code uses only `nativeInit`/`nativePush64`/`nativeCall`, `scriptRegister`, `keyboardHandlerRegister`. |
| The Windows kernel32 shared-memory backend (`CreateFileMappingW` etc. via FFM) | The call shapes are checked against the JDK's handle types and UTF-16 strings, but never executed on Windows. The file-backed backend, used for every test here, is the same code path above the mapping. |
| That a given GTA prop model really is ~1 m, and how it behaves as a collidable frozen object | Unknowable offline. This is why `model = auto` measures candidates with `GET_MODEL_DIMENSIONS` at start-up and logs the result. |
| Frame-time cost with ~1500 live props in the real engine | The budgets (spawns per frame, microsecond cap, pooling) bound the work; the real-engine cost is not measured. |

## Install

### Minecraft side (a Fabric server or a normal client/server)

1. JDK 25, Minecraft **26.3**, Fabric Loader ≥ 0.19.5, Fabric API.
2. Put `orbital-passthrough-1.0.0.jar` in `mods/`. Add the JVM flag `--enable-native-access=ALL-UNNAMED` (otherwise Java prints a one-time warning; the Windows backend uses the Foreign Function API).
3. For a headless background server: `java -jar fabric-server-launch.jar nogui`. There is no client code in the mod. The server never auto-pauses for lack of players while GTA is attached, and your `server.properties` is left alone.
4. `config/orbital-passthrough.properties` is written with documented defaults on first start.

### GTA V side (**single player only**)

1. ScriptHookV installed (<http://www.dev-c.com/gtav/scripthookv/>) and an ASI loader.
2. Copy `OrbitalPassthrough.asi` and `OrbitalPassthrough.ini` into the GTA V folder.
3. Start Minecraft and GTA V in any order. Both processes must run at the **same elevation** (both normal or both admin), as the shared memory uses default security.

Do not use this in GTA Online: the mod detects an online session and switches itself off, but ScriptHookV mods there risk a ban.

### First run

1. Walk to a flat spot in GTA V. The overlay (top left) says `NEEDS ANCHOR` once Minecraft is connected.
2. **F9** pins the Minecraft world to where you stand: Minecraft's spawn point becomes your feet. Streaming starts; props appear nearest first.
3. Check `OrbitalPassthrough.log` for the `[calibrate]` lines: that is the model the mod picked and how close to 1 m it measured. If none fit, set `[render] model =` to a prop that is a 1 m cube in your game build.

| Key | Action |
|---|---|
| F9 | pin the world at your feet |
| F7 / F6 | mine / place at the crosshair |
| `[` `]` | choose the block to place |
| F10 | toggle the overlay |
| F8 | re-measure candidate models |
| shooting | bullets damage blocks (a block breaks when accumulated damage reaches its hardness × `damagePerHardness`); rockets blast a sphere |
| driving into blocks | impact energy breaks the contact block and up to two behind it |

## How it works

**Only the visible shell is streamed.** GTA V cannot hold millions of objects. Minecraft scans each 16³ section, keeps voxels that have at least one exposed face, and sends them as 4 bytes each. A neighbour in an unloaded chunk counts as opaque, and the same rule is used for the bulk scan and for incremental updates, so they cannot disagree. Per section it remembers exactly what GTA has, so a block change re-evaluates seven voxels and sends only the difference: breaking a block reveals the one behind it, placing one hides what it covers.

**The prop pool is a cache, not a mirror.** Under a hard cap (default 1500), the voxels nearest the player get props, nearest first, with hysteresis between spawn and retire radius. Retired props are parked and reused rather than destroyed and recreated; a few props per frame are existence-checked and respawned if the engine removed them; if the engine refuses to create more objects the cap shrinks to what it accepts and spawning backs off. A break removes its prop the same frame.

**GTA's own physics is the physics.** Props are frozen, collidable objects, so the player, peds and vehicles stand on and crash into them natively. The mod reads back: a line-of-sight probe from the camera through GTA's reported bullet impact point (accepted only if the first thing hit is one of our props), crosshair probes for the mining/placing keys, and per-frame speed loss of the player or vehicle against the voxel shell for impacts. The player's rotated hitbox is published every frame; Minecraft uses it to refuse placing a block inside you.

**Minecraft never trusts GTA.** Every request is rate limited, bounds checked, range checked against the position you last reported, and answered with an `ACK` naming the reason when refused. Unbreakable blocks stay unbreakable.

**Coordinates.** One block = one metre. Minecraft (+X east, +Y up, +Z south) maps to GTA (+X east, +Y north, +Z up) by a pure rotation, so nothing is mirrored. See [protocol/PROTOCOL.md](protocol/PROTOCOL.md) for the wire format, handshake and the exact transform.

## Latency

Measured on this machine's Linux file-backed mapping, against the real Java server: round trip GTA → Minecraft server thread → GTA, 6000 samples with the GTA side busy-polling:

```
min ~10 us   p50 ~110 us   p90 ~180 us   p99 0.3-0.9 ms   p99.9 3-7 ms   max 5-30 ms (varies run to run)
```

So the transport and Minecraft's wake-up are far inside 16 ms. Three honest caveats:

- **Frame granularity.** GTA's script runs once per frame, so a request leaves at a frame boundary and its result is applied at the next: a full interaction costs about two GTA frames (the scenario test, at an 8 ms frame, measures ~16 ms from key press to the block vanishing). Sub-16 ms end to end therefore needs ≥ ~120 fps; at 60 fps expect ~2 frames.
- **The tail is Minecraft's tick.** The server runs queued tasks only between ticks, so a request that lands mid-tick waits for it (a few ms; a heavy tick, such as chunk generation, can be tens of ms). Across repeated benchmark runs, between 0 and 2 round trips in 6000 exceeded 16 ms (worst 30 ms) for that reason.
- **Windows is not measured.** There the Java reader sleeps on a kernel event that GTA sets after publishing instead of polling at ~40 µs; that should be at least as fast, but I could not run it.

## Limitations

- Blocks are 1 m cubes. Slabs, stairs and glass are drawn as cubes; blocks that fill under 40% of their cell (plants, torches, rails, carpets, ladders, doors, trapdoors, panes, fences) and fluids are **not drawn**. Appearance is whatever prop model(s) you configure: there are no per-block textures.
- Streaming reaches `spawn_radius` (22 m) around you, not the whole Minecraft world.
- Minecraft entities (mobs, items), lighting, sky and weather are not mirrored. The GTA player is not an entity in Minecraft.
- Any process running as your user can write the shared memory and so drive the server; it is not an authentication boundary.
- Targets Minecraft 26.3 specifically (the 26.x API is unobfuscated and changed a lot; for example `Level.getDayTime` is gone).

## Build from source

```sh
# everything that can run without GTA V (C++ tests incl. sanitizers, CMake, Java tests + layout parity, MinGW DLL, end to end)
JAVA_HOME=/path/to/jdk25 tools/run_all.sh

# Minecraft mod only
cd minecraft && ./gradlew build            # -> build/libs/orbital-passthrough-1.0.0.jar

# GTA plugin (Windows, MSVC 2022, CMake >= 3.20; needs the ScriptHookV SDK, which is not redistributed here)
cmake -S gta -B build -A x64 -DSHV_SDK_DIR=C:/path/to/ScriptHookV_SDK
cmake --build build --config Release       # -> OrbitalPassthrough.asi
```

## Layout

```
protocol/orbital_ipc.h      wire format + lock-free rings + seqlocks (C++), PROTOCOL.md
gta/src/core/               portable logic: bridge, world sync, voxel store, prop manager, interaction, app
gta/src/shv/                ScriptHookV glue: natives (verified hashes), script thread, DllMain
gta/tests/                  unit tests, mock game, SDK stub, end-to-end client, latency benchmark
minecraft/src/main/java/com/orbitalcannon/passthrough/
  ipc/                      Protocol, shared-memory backends (Windows FFM / file), rings, bridge, watcher
  world/                    palette, scanner, streamer, change tracking, chunk tickets
  interact/                 break / place / collision execution
  mixin/                    block-change hook, "never pause while GTA is attached"
tools/                      run_all.sh, e2e.sh
```
