# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ZstdNet is a Minecraft Java Edition mod that transparently compresses relayed
client↔server traffic with ZSTD (via `zstd-jni`) to cut public bandwidth in
high-repetition scenarios (Create-based / large modpack servers, FRP/NAT-traversal
setups). It works by running a **TCP proxy** that compresses Minecraft packets, with
**raw UDP passthrough** on the same local port for voice chat and same-port-UDP mods
(Simple Voice Chat, Sable / Create Aeronautics).

The end-user docs are the authoritative spec for runtime behavior, config keys, and
commands — read them before changing proxy/config semantics:
`README.en-US.md` and `README.zh-CN.md`.

## Repository layout — multi-loader monorepo

This is **not a single Gradle project**. It is five parallel, independent mod projects
(each with its own `build.gradle` / `settings.gradle` — there is no root settings.gradle),
plus a shared **`mods/common`** source-only module that is the single source of truth for the
loader-agnostic core:

```
mods/common                      single-source core (compiled into every variant)
mods/1.20.1/zstdnet-forge        Forge 1.20.1        JDK 17
mods/1.20.1/zstdnet-neoforge     NeoForge 1.20.1     JDK 17  (reuses forge's integration layer)
mods/1.20.1/zstdnet-fabric       Fabric 1.20.1       JDK 17
mods/1.21.1/zstdnet-neoforge     NeoForge 1.21.1     JDK 21
mods/1.21.1/zstdnet-fabric       Fabric 1.21.1       JDK 21
```

All variants share the Java package `cn.tohsaka.factory.zstdnet`. **The loader-agnostic core
lives once in `mods/common`** — `core/`, `proxy/LocalZstdNet`, `proxy/AndroidZstdNativeLoader`,
`server/ServerProxyRuntime`, `server/ServerProxyConfigFile`, `server/DedicatedServerAutoPort`,
`ZstdServerList`, the `platform/` SPI, plus shared resources (lang files, Android `.so` natives)
and the unit tests. Each variant's `build.gradle` pulls it in:

```groovy
sourceSets {
    main { java.srcDir '../../common/src/main/java'; resources.srcDir '../../common/src/main/resources' }
    test { java.srcDir '../../common/src/test/java' }
}
```

so common is compiled inside each variant's own classpath + mappings (all three loaders use
official Mojang mappings, so `net.minecraft.*` names match everywhere — this is why the core can
be one shared copy). **Change shared logic once in `mods/common`; it propagates to every variant.**
Only the thin loader-integration layer is per-variant. `mods/1.20.1/zstdnet-neoforge` additionally
borrows forge-1.20.1's integration layer via `srcDir '../zstdnet-forge/src/main/java'` (NeoForge
1.20.1 shares Forge's `net.minecraftforge.*` namespace), so it carries no Java of its own.

The handful of loader-specific touch points the core needs are abstracted behind a tiny
**Platform SPI** in `mods/common`: `platform/Platform` (interface) + `platform/Platforms` (holder)
+ `platform/DefaultPlatform` (fallback), exposing `configDir()`, `isClient()`, and
`adjustHandshakeHostSuffix()` (Forge's `\0FML2\0` handshake marker). Each variant provides its
implementation (`ForgePlatform` / `NeoForgePlatform` / `FabricPlatform`) and registers it as the
first line of its `Zstdnet` main entry via `Platforms.set(...)`. To add a loader touch point to
shared code, add a method to `Platform` and implement it in each variant rather than forking the
core file.

**Adding a new loader/version variant?** Follow `ADDING_A_VARIANT.md` — a step-by-step guide
(fetch dep versions, scaffold the thin layer, wire to common, provide a `Platform` impl, fix the
per-MC-version mixin/coremod targets, build & verify).

`net/minecraft/...` at the repo root are clean modified-vanilla reference sources
(`ShareToLanScreen`, `PublishCommand`) showing the intended screen/command modifications.
They are not part of any build — treat them as reference only.

## How loaders differ (the per-variant integration layer)

Everything a variant keeps for itself lives in its own `src/main/java`: `Zstdnet` (main entry,
registers the Platform impl), `ClientConfig`, `client/ClientProxyPublisher`, `network/LanCompressionSync`,
`server/ServerProxyBootstrap`, `coremod/*Hooks`, the `platform/*Platform` impl, and (Fabric only)
`client/ZstdnetClient` + `mixin/*`. Everything else is shared from `mods/common`.

The same three vanilla patch points are implemented two ways:

- **Forge / NeoForge** use **coremods**: JS ASM transformers in
  `src/main/resources/coremods/*.js`, registered via `META-INF/coremods.json`. They inject
  calls into static hook classes in `coremod/*Hooks.java`.
- **Fabric** uses **Mixins**: `src/main/java/.../mixin/*.java` + `zstdnet.mixins.json`. The
  Fabric variant *also* keeps the `coremod/*Hooks.java` classes — the mixins call into the
  same hook methods, so the hook logic stays shared and only the injection mechanism changes.

The three patch points (identical intent across loaders):
`zstdnet_connect_screen_intercept` (client connection takeover), `zstdnet_lan_compression_threshold`,
`zstdnet_server_real_ip` (PROXY protocol v2 real-IP).

Fabric also has a separate `client` entrypoint (`client/ZstdnetClient`); Forge/NeoForge
gate client init via `FMLEnvironment.dist`.

## Architecture (package `cn.tohsaka.factory.zstdnet`)

Entries tagged **(common)** live once in `mods/common`; the rest are per-variant (see above).

- `Zstdnet` — main entry. Registers the Platform impl (`Platforms.set(...)`), then inits
  `ClientProxyPublisher` (client only), `LanCompressionSync`, and `ServerProxyBootstrap`.
- `platform/` **(common)** — the **Platform SPI**: `Platform` interface, `Platforms` holder
  (defaults to `DefaultPlatform`), abstracting `configDir()` / `isClient()` /
  `adjustHandshakeHostSuffix()`. Each variant ships its own `*Platform` impl.
- `proxy/LocalZstdNet` **(common)** — **client-side local proxy**. Opens a local TCP listener, takes over
  the connection, compresses client→server, and provides raw UDP passthrough on the same local
  port. Has `Mode` AUTO/RAW/ZSTD.
- `server/ServerProxyRuntime` **(common)** — **server-side proxy runtime** (the largest file, ~2.4k lines).
  Listens on the entry port, does bidirectional forwarding (decompress client→backend, compress
  backend→client), rate limiting, anti-spam/ban, traffic stats, PROXY v2 real-IP restoration,
  voice UDP passthrough, and vanilla status-ping passthrough.
- `server/ServerProxyBootstrap` — hooks the loader server lifecycle (started/stopping/tick/login)
  to start/stop the runtime.
- `server/DedicatedServerAutoPort` **(common)** — `auto_takeover`: reads `server.properties` `server-port`,
  keeps it as the public entry, and moves the backend Minecraft server to a free local port.
- `server/ServerProxyConfigFile` **(common)** — reads/writes the auto-maintained `zstdnet-server.properties`
  (rewritten with a commented template on change; supports hot reload).
- `client/ClientProxyPublisher` — client runtime: leaves vanilla server entries untouched and
  swaps the connection target to the local proxy; registers `/zstdhud` and `/zstdport` commands;
  adds the Zstd-port field to the "Open to LAN" screen; renders the HUD.
- `ClientConfig` — client TOML config (compression `level`).
- `network/LanCompressionSync` — syncs LAN compression-threshold state.
- `core/` **(common)** — shared building blocks: `io/` (counting streams, `StreamTransfer`),
  `limit/TokenBucketLimiter` (rate limiting), `protocol/` (`VarIntCodec`/`VarIntRead`/`PacketIo`/
  `ByteArrayOps` — Minecraft packet framing), `stats/TrafficStats`.
- `proxy/AndroidZstdNativeLoader` **(common)** — loads bundled Android `.so` natives from
  `resources/zstdnet/android/<abi>/` (zstd-jni ships no Android natives), for Pojav/Android clients.

Runtime config files live in Minecraft's `config/`: `zstdnet-client.toml` (client) and
`zstdnet-server.properties` (server, auto-maintained). Player-facing commands: `/zstdhud`
(toggle HUD) and `/zstdport` (view/change ports in singleplayer/LAN hosting).

## Build

Builds use an **external build root** at `../zstdnet-build` (a sibling of this repo, gitignored).
It must contain a Gradle 8.8 distribution at `tools/gradle-8.8` and holds all caches and output
jars — `build.gradle` redirects `layout.buildDirectory` there, so there is no in-repo `build/`.
Builds use this external `gradle.bat` directly (not the in-repo wrapper). Required JDKs:
`C:\Program Files\Java\jdk-17` and `C:\Program Files\Java\jdk-21`.

PowerShell build scripts at the repo root pick the right JDK and gradle invocation:

```powershell
.\build-forge.ps1                              # Forge 1.20.1 (JDK 17)
.\build-neoforge.ps1                           # NeoForge 1.21.1 (JDK 21, default)
.\build-neoforge.ps1 -MinecraftVersion 1.20.1  # NeoForge 1.20.1 (JDK 17)
```

To build a single variant manually, set `JAVA_HOME`/`PATH` to the matching JDK and run, from
the variant's project dir:
`& ..\..\..\..\zstdnet-build\tools\gradle-8.8\bin\gradle.bat --project-cache-dir <cache> build`

On Forge/NeoForge, `jarJar` bundles `zstd-jni` into the final jar (a slim jar is built then
deleted in favor of the all-in-one). The mod version is in each variant's `gradle.properties`
(`mod_version`).

## Tests & regression

- **Unit tests** (JUnit 5) now live once in `mods/common/src/test/java`
  (`ServerProxyRuntimeVoiceChatTest`, `LocalZstdNetUdpPassthroughTest`) and run in every variant
  via the shared `test` srcDir. Run with the variant's gradle: `gradle test` (single test:
  `gradle test --tests '*VoiceChatTest'`).
- **Full regression** (`scripts/`, run from the repo via the external gradle):
  - `scripts/run-regression.ps1 [-SkipBuild] [-SkipDedicated] [-SkipLan]` — builds all five
    variants, then runs dedicated-server startup verification and LAN regression verification.
  - `scripts/verify-runtime-startup.ps1` — dedicated-server startup checks.
  - `scripts/verify-lan-regression.ps1` — LAN hosting / "Open to LAN" checks.

## Conventions

- Javadoc and inline comments are frequently written in Chinese; match the surrounding style of
  the file you are editing.
- Source files carry an MIT license header — keep it on new files.
