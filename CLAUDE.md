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

This is **not a single Gradle project**. It is fourteen parallel, independent projects
(thirteen mod variants + one Bukkit/Spigot plugin; each with its own `build.gradle` / `settings.gradle`
— there is no root settings.gradle), plus a shared **`mods/common`** source-only module that is the
single source of truth for the loader-agnostic core:

```
mods/common                      single-source core (compiled into every variant)
mods/1.16.5/zstdnet-forge        Forge 1.16.5        JDK 8   (MCP mappings, FG5 + Gradle 7.6.4; see §13)
mods/1.18.2/zstdnet-forge        Forge 1.18.2        JDK 17
mods/1.19.3/zstdnet-fabric       Fabric 1.19.3       JDK 17  (pre-1.19.4 GUI: PoseStack render, x/y widget fields)
mods/1.19.2/zstdnet-forge        Forge 1.19.2        JDK 17
mods/1.20.1/zstdnet-forge        Forge 1.20.1        JDK 17
mods/1.20.1/zstdnet-neoforge     NeoForge 1.20.1     JDK 17  (reuses forge's integration layer)
mods/1.20.1/zstdnet-fabric       Fabric 1.20.1       JDK 17
mods/1.21.1/zstdnet-neoforge     NeoForge 1.21.1     JDK 21
mods/1.21.1/zstdnet-fabric       Fabric 1.21.1       JDK 21
mods/26.1/zstdnet-neoforge       NeoForge 26.1       JDK 25  (un-obfuscated; covers 26.1.x = 26.1.1/26.1.2)
mods/26.1/zstdnet-fabric         Fabric 26.1         JDK 25  (un-obfuscated Loom; covers 26.1.x)
mods/26.2/zstdnet-neoforge       NeoForge 26.2       JDK 25  (un-obfuscated; NeoForge 26.2.x still beta; see §14)
mods/26.2/zstdnet-fabric         Fabric 26.2         JDK 25  (un-obfuscated Loom; see §14)
mods/bukkit/zstdnet-bukkit       Bukkit/Spigot/Paper plugin (server-only)  JDK 17 bytecode, version-independent
```

> **MC 26.1+ (the un-obfuscated era)** needs a different toolchain (JDK 25, Gradle 9.x, new Fabric
> Loom, many vanilla API renames). See `ADDING_A_VARIANT.md` §10 for the full 26.1 reference and §14
> for the 26.1 → 26.2 deltas (26.2 reworked the client GUI: `ShareToLanScreen` → `MultiplayerOptionsScreen`,
> `Minecraft.screen`/`Options.hideGui`/`Connection.isEncrypted()` all moved). The Bukkit plugin is
> version-independent and runs on 26.1.x / 26.2.x servers unchanged (no rebuild needed).

The **`mods/bukkit/zstdnet-bukkit`** plugin is server-side only (no mod loader). It reuses the same
`mods/common` proxy core but, because a plugin loads *after* the MC port is bound, it cannot relocate
the backend — it runs the proxy on a **separate listen port** (`auto_takeover=false`) and forwards to
the local MC server. It compiles against Spigot-API (`compileOnly`) + slf4j (`compileOnly`, provided
by Paper/Spigot at runtime), bundles `zstd-jni` via the `com.gradleup.shadow` fat-jar **relocated** to
`cn.tohsaka.factory.zstdnet.shaded.com.github.luben.zstd` (so it can't clash with a host-provided
zstd-jni, e.g. Mohist's), and targets Java 17 bytecode so one jar runs on both 1.20.1
(JDK 17) and 1.21.x (JDK 21) servers. It **excludes** `server/DedicatedServerAutoPort.java` from its
source set (that file is the only common file importing `net.minecraft.*`; the plugin never relocates
ports). To make that exclusion possible, the MC-free pieces of auto-takeover were split out of
`DedicatedServerAutoPort` into `server/AutoPortPlan` (record) + `server/DedicatedAutoPortState` (active-plan
holder), which the core (`ServerProxyRuntime`) and the per-variant bootstraps now reference instead of the
MC-coupled class. The plugin's own thin layer is `Zstdnet` (JavaPlugin entry), `platform/BukkitPlatform`,
and `server/ServerProxyBootstrap` (same package as the core, so it can call the package-private
`start/stop/...`); config defaults are written via `ServerProxyConfigFile.writePluginDefaults(...)`.

All variants share the Java package `cn.tohsaka.factory.zstdnet`. **The loader-agnostic core
lives once in `mods/common`** — `core/`, `proxy/LocalZstdNet`, `proxy/AndroidZstdNativeLoader`,
`server/ServerProxyRuntime`, `server/ServerProxyConfigFile`, `server/DedicatedServerAutoPort`
(+ its MC-free split `server/AutoPortPlan` / `server/DedicatedAutoPortState`),
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
It must contain a Gradle 8.8 distribution at `tools/gradle-8.8` (and, for MC 26.1+, a Gradle 9.x
distribution at `tools/gradle-9.4.1`) and holds all caches and output jars — `build.gradle` redirects
`layout.buildDirectory` there, so there is no in-repo `build/`. Builds use the external `gradle.bat`
directly (not the in-repo wrapper). Required JDKs: `C:\Program Files\Java\jdk-17`,
`C:\Program Files\Java\jdk-21`, and (for 26.1) JDK 25 (`C:\Users\78569\.jdks\liberica-25.0.3`).

**All final jars land in one shared directory `../zstdnet-build/dist/`** (not the scattered
per-variant `mods/<ver>/<variant>/libs/`). Every variant's `build.gradle` redirects its final
artifact task's `destinationDirectory` there — `jarJar` (Forge 1.18.2/1.19.2/1.20.1 + NeoForge
1.20.1), `shadowJar` (NeoForge 1.21.1/26.1 + Bukkit), or `remapJar` (Fabric). The filename already
encodes the MC version + loader (`zstdnet-<mc>-<loader>-<mod_version>.jar`, e.g.
`zstdnet-1.21.1-neoforge-1.4.2-m2.jar`; Bukkit is `zstdnet-bukkit-<mod_version>.jar`), so the ten
jars coexist in one folder with no collision. The transient slim/thin/dev jars stay in each variant's
own `libs/` (and the Forge/NeoForge/Bukkit variants delete theirs after build).

PowerShell build scripts at the repo root pick the right JDK and gradle invocation:

```powershell
.\build-forge.ps1                              # Forge 1.20.1 (JDK 17, default)
.\build-forge.ps1 -MinecraftVersion 1.19.2     # Forge 1.19.2 (JDK 17)
.\build-neoforge.ps1                           # NeoForge 1.21.1 (JDK 21, default)
.\build-neoforge.ps1 -MinecraftVersion 1.20.1  # NeoForge 1.20.1 (JDK 17)
.\build-neoforge.ps1 -MinecraftVersion 26.1    # NeoForge 26.1 (JDK 25, Gradle 9.4.1)
.\build-neoforge.ps1 -MinecraftVersion 26.2    # NeoForge 26.2 (JDK 25, Gradle 9.4.1; 26.2.x still beta)
.\build-fabric.ps1   -MinecraftVersion 26.1    # Fabric 26.1 (JDK 25, Gradle 9.4.1); also supports 1.20.1/1.21.1
.\build-fabric.ps1   -MinecraftVersion 26.2    # Fabric 26.2 (JDK 25, Gradle 9.4.1)
.\build-bukkit.ps1                             # Bukkit/Spigot/Paper plugin (JDK 17, version-independent)
.\build-all.ps1                                # all 14 variants serially into ../zstdnet-build/dist/
```

To build a single variant manually, set `JAVA_HOME`/`PATH` to the matching JDK and run, from
the variant's project dir:
`& ..\..\..\..\zstdnet-build\tools\gradle-8.8\bin\gradle.bat --project-cache-dir <cache> build`

How `zstd-jni` is bundled differs by variant family:
- **Forge 1.18.2 / 1.19.2 / 1.20.1** (and forge-style `1.20.1-neoforge`): `jarJar` embeds the
  un-relocated `zstd-jni` as a nested jar (a slim jar is built then deleted in favor of the all-in-one).
- **NeoForge 1.21.1 / 26.1** (ModDevGradle) and the **Bukkit** plugin: `com.gradleup.shadow` flattens
  `zstd-jni` into the final jar and **relocates** `com.github.luben.zstd` →
  `cn.tohsaka.factory.zstdnet.shaded.com.github.luben.zstd`, dropping the nested module entirely
  (`shadowJar` is the artifact, classifier `''`; the slim/thin `jar` is deleted). This is what lets the
  jar run on hosts that ship their own zstd-jni — e.g. **Mohist injects `zstd-jni` into the boot module
  layer**, and an un-relocated bundle would crash with JPMS *"reads more than one module named
  com.github.luben.zstd_jni"*. Relocation is **native-safe**: zstd-jni's native libs live at the **jar
  root** (`linux/amd64/…`, `win/amd64/…`, not under the package) and load by absolute classpath path +
  `os.name`/`os.arch`, none of which a package rename touches. (The remaining Forge variants would hit
  the same Mohist clash; relocating them means staging shadow *before* ForgeGradle's `reobf` — not yet done.)

The mod version is in each variant's `gradle.properties` (`mod_version`).

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
- Source files carry a CC BY-NC-SA 4.0 license header (see any `.java` for the exact block) — keep it on new
  files. The project is a derivative of wish's MIT-licensed ZstdNet; wish's original MIT grant is retained in
  `NOTICE`, while the project as a whole is CC BY-NC-SA 4.0 (`LICENSE`). Both `LICENSE`/`NOTICE` are mirrored into
  `mods/common/src/main/resources/` (bundled into every jar) — update both copies together.
