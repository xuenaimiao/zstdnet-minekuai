# Adding a new loader / version variant

This guide is for **adding a new variant** (a loader × Minecraft-version combination) to
ZstdNet, e.g. "Fabric 1.21.4" or "Forge 1.19.2". Read `CLAUDE.md` first for the overall
architecture. This document assumes the **single-source-of-truth** layout: the core lives once
in `mods/common`, and a variant is just a thin loader-integration layer that pulls common in.

> **Mental model.** You are **not** copying the core. The ~4200-line core (`ServerProxyRuntime`,
> `LocalZstdNet`, `core/**`, …) already lives in `mods/common` and is compiled into every variant
> via `sourceSets.srcDir`. Adding a variant = (1) scaffold a thin project, (2) wire it to common,
> (3) provide a `Platform` impl, (4) fix the per-MC-version mixin/coremod targets. **Never edit or
> duplicate `mods/common` to add a variant** — only touch it if the *shared* logic itself changes.

> **Plugin (non-loader) variants.** For a Bukkit/Spigot/Paper plugin there is no mod loader, mixins,
> or coremods — see `mods/bukkit/zstdnet-bukkit` as the reference. Differences from a mod variant:
> server-only (no client layer); `BukkitPlatform.configDir()` points at the plugin data folder;
> the bootstrap runs the proxy on a **separate listen port** (`auto_takeover=false`, written via
> `ServerProxyConfigFile.writePluginDefaults`); `zstd-jni` is bundled with `com.gradleup.shadow`
> instead of `jarJar`/`include`; and the source set **excludes** `server/DedicatedServerAutoPort.java`
> (the lone `net.minecraft.*`-importing common file) — its MC-free parts live in
> `server/AutoPortPlan` + `server/DedicatedAutoPortState`.

---

## 0. Decide the target and pick a template

- **Loader**: Forge / NeoForge / Fabric.
- **MC version** → **JDK**: roughly MC ≤ 1.20.4 → **JDK 17**; MC 1.20.5 / 1.21+ → **JDK 21**
  (always match the Java version that MC release requires).
- **Template variant to copy** = the *closest existing variant*, in priority order:
  1. Same loader, nearest MC version (best — minimal porting). E.g. new Fabric 1.21.4 → copy
     `mods/1.21.1/zstdnet-fabric`.
  2. If the loader is new for that MC line, the same MC version's other loader.

Existing variants (templates):

```
mods/1.19.2/zstdnet-forge        Forge 1.19.2      JDK 17   coremods
mods/1.20.1/zstdnet-forge        Forge 1.20.1      JDK 17   coremods
mods/1.20.1/zstdnet-neoforge     NeoForge 1.20.1   JDK 17   coremods, BORROWS forge's integration layer (see §7b)
mods/1.20.1/zstdnet-fabric       Fabric 1.20.1     JDK 17   mixins
mods/1.21.1/zstdnet-neoforge     NeoForge 1.21.1   JDK 21   coremods, full own integration layer
mods/1.21.1/zstdnet-fabric       Fabric 1.21.1     JDK 21   mixins
```

---

## 1. Fetch the dependency versions (authoritative, scriptable)

Plug a `<mc>` version in. These are the values that go into `gradle.properties`.

| `gradle.properties` key | Loader | How to fetch the latest |
|---|---|---|
| `forge_version` | Forge | `curl -s https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml \| grep -oE "<version><mc>-[0-9.]+</version>" \| tail -1` → use the part **after** `<mc>-` |
| `neo_version` (1.21+) | NeoForge | `curl -s https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml \| grep -oE "<version>YY\.X\.[0-9]+</version>" \| tail -1` (NeoForge numbers by MC minor: 1.21.1→`21.1.x`, 1.21→`21.0.x`, 1.20.6→`20.6.x` …) |
| `neo_version` (1.20.1) | NeoForge | **legacy scheme** `1.20.1-47.1.x` under coords `net.neoforged:forge` — see `mods/1.20.1/zstdnet-neoforge/gradle.properties` |
| `loader_version` | Fabric | `curl -s https://meta.fabricmc.net/v2/versions/loader/<mc> \| head` → first `"version"` |
| `fabric_api_version` | Fabric | `curl -s 'https://api.modrinth.com/v2/project/fabric-api/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22<mc>%22%5D' \| grep -oE '"version_number":"[^"]+"' \| head -1` |
| (sanity) MC list | — | `https://launchermeta.mojang.com/mc/game/version_manifest_v2.json` |

---

## 2. Scaffold the directory

Copy the template, then **delete the build outputs/caches** if any were copied. Example (new
Fabric 1.21.4 from Fabric 1.21.1):

```bash
cp -r mods/1.21.1/zstdnet-fabric mods/1.21.4/zstdnet-fabric
```

A variant contains only these (everything else comes from `mods/common`):

```
<variant>/
  build.gradle              # wired to common (see §4)
  settings.gradle           # just rootProject.name
  gradle.properties         # MC + dependency versions (§3)
  src/main/java/cn/tohsaka/factory/zstdnet/
      Zstdnet.java                      # main entry — registers Platform (§6)
      ClientConfig.java                 # loader-specific config API
      client/ClientProxyPublisher.java
      network/LanCompressionSync.java
      server/ServerProxyBootstrap.java
      coremod/ConnectScreenHooks.java   # hook bodies (shared shape, loader-specific bits)
      coremod/LanCompressionHooks.java
      coremod/ServerRealIpHooks.java
      platform/<Loader>Platform.java    # the SPI impl (§6)
      # Fabric only:
      client/ZstdnetClient.java
      mixin/*.java
  src/main/resources/
      META-INF/mods.toml            (Forge)   |  neoforge.mods.toml (NeoForge)  |  fabric.mod.json (Fabric)
      META-INF/coremods.json + coremods/*.js   (Forge/NeoForge only)
      zstdnet.mixins.json                       (Fabric only)
      pack.mcmeta
      # NOTE: lang files and android/*.so natives are NOT here — they live in mods/common.
```

If you copied a variant that still had `src/main/resources/zstdnet/android/*.so` or
`assets/zstdnet/lang/*.json`, **delete them** — those are shared from `mods/common` and a
duplicate path can collide in `processResources`.

---

## 3. `gradle.properties` — set versions

```properties
minecraft_version=<mc>
# then the loader-specific dependency key(s) from §1:
forge_version=<...>            # Forge
# or
neo_version=<...>             # NeoForge
# or
loader_version=<...>          # Fabric
fabric_api_version=<...>      # Fabric
```

`mod_version` is the shared mod version — keep it in sync with the other variants.

---

## 4. `build.gradle` — wire to common + set JDK + dep coords

The single most important block (already present in every existing variant). Confirm it exists,
verbatim, near the top (after `group = mod_group_id`):

```groovy
// 共享核心源码：单一真源在 mods/common，被所有变体作为额外源根编译
sourceSets {
    main {
        java.srcDir '../../common/src/main/java'
        resources.srcDir '../../common/src/main/resources'
    }
    test {
        java.srcDir '../../common/src/test/java'
    }
}
```

> The `'../../common'` path is relative to the variant's project dir (`mods/<mc>/<variant>`), so
> it resolves to `mods/common`. If you nest the variant at a different depth, fix the `..` count.

Also update in `build.gradle`:
- **JDK toolchain**: `JavaLanguageVersion.of(17)` or `of(21)` to match the MC version.
- **Dependency coordinates** that reference the version keys (`minecraft`, `forge`/`neoforge`/
  `fabric-loader` + `fabric-api`). These usually already use `${...}` interpolation, so §3 covers them.
- The `archivesName`, `layout.buildDirectory`, and `runDir` paths use `${minecraft_version}` —
  no change needed.

---

## 5. `settings.gradle`

Only one line matters:

```groovy
rootProject.name = 'zstdnet-<loader>'
```

(plus the loader's `pluginManagement` repositories — keep what the template has.)

---

## 6. `Platform` implementation + registration

The core reaches the loader through `cn.tohsaka.factory.zstdnet.platform.Platform` (in
`mods/common`). Each variant ships an impl and registers it.

- **Reuse if identical**: Fabric variants can copy `platform/FabricPlatform.java` verbatim. Forge
  variants copy `ForgePlatform.java`. (They only use loader APIs, no MC-version-sensitive code.)
- **The 3 methods**:
  - `configDir()` — Fabric: `FabricLoader.getInstance().getConfigDir()`; Forge/NeoForge:
    `FMLPaths.GAMEDIR.get().resolve("config")`.
  - `isClient()` — Fabric: `getEnvironmentType() == EnvType.CLIENT`; Forge/NeoForge:
    `FMLEnvironment.dist == Dist.CLIENT`.
  - `adjustHandshakeHostSuffix(String)` — default returns input unchanged; **Forge** overrides to
    ensure the `\0FML2\0` marker (see `ForgePlatform`). NeoForge/Fabric use the default.
  - Forge vs NeoForge differ only by package: `net.minecraftforge.*` vs `net.neoforged.*`.
- **Register it** as the *first line* of the variant's `Zstdnet` main entry:
  ```java
  Platforms.set(new <Loader>Platform());
  ```
  (Forge/NeoForge: in the mod constructor. Fabric: top of `onInitialize()`.)
  If you skip this, the core falls back to `DefaultPlatform` (relative `config` dir, server
  environment) — fine for unit tests, **wrong for a real client**, so don't skip it.

---

## 7. Per-MC-version porting — the only place real work happens

The core compiles unchanged across MC versions (official Mojang mappings keep `net.minecraft.*`
names stable for the APIs it uses). What **does** change between MC versions is the patch
injection layer, because it targets specific vanilla classes/methods:

### 7a. Fabric — mixins
- `src/main/java/.../mixin/*.java`: `@Mixin` targets and `@Inject`/`@Redirect` `method = "..."`
  descriptors must match the **new MC version's** vanilla signatures (use the official-mapped
  names). Compare the nearest existing Fabric variant's mixins and fix any that drifted.
- `src/main/resources/zstdnet.mixins.json`: ensure every listed mixin class exists; set
  `"compatibilityLevel"` and `"minVersion"` appropriately.

### 7b. Forge / NeoForge — coremods
- `src/main/resources/coremods/*.js`: the ASM transformers match methods by name/descriptor.
  If a targeted vanilla method's name/signature changed in the new MC version, update the matcher.
- `META-INF/coremods.json`: maps coremod id → JS file; keep in sync.
- **NeoForge 1.20.1 special case**: it shares Forge's `net.minecraftforge.*` namespace, so
  `mods/1.20.1/zstdnet-neoforge` carries **no Java of its own** — its `build.gradle` borrows
  forge's integration layer *and* common:
  ```groovy
  sourceSets {
      main { java.srcDir '../zstdnet-forge/src/main/java'; java.srcDir '../../common/src/main/java'
             resources.srcDir '../../common/src/main/resources' }
      test { java.srcDir '../zstdnet-forge/src/test/java';  java.srcDir '../../common/src/test/java' }
  }
  ```
  NeoForge **1.21+** is a clean fork (`net.neoforged.*`) and has its own full integration layer.

### 7c. Mod metadata (version-sensitive fields)
- **Fabric** `fabric.mod.json`: `"depends"` → `"minecraft": "~<mc>"`, `"java": ">=17|21"`,
  `"fabricloader": ">=${loader_version}"`.
- **Forge** `META-INF/mods.toml` / **NeoForge** `META-INF/neoforge.mods.toml`: the
  `[[dependencies]]` `versionRange` for `minecraft` (e.g. `[1.21.1,1.22)`) and `neoforge`/`forge`,
  plus `javaVersion="[17,)"`/`[21,)"`.

---

## 8. Build & verify

Builds use the external Gradle at `../zstdnet-build/tools/gradle-8.8` and write outputs there.
**On this machine the JDKs are not at the paths `build-*.ps1` hardcode** — see
`CLAUDE.md` / the build-toolchain note. Invoke gradle directly with the right `JAVA_HOME`:

```powershell
# pick JAVA_HOME for the MC version:
#   JDK 17  -> D:\JDK   (whatever JDK17 you have)
#   JDK 21  -> D:\dev\MCmod\zstdnet-build\tools\jdk-21.0.11+10  (or any JDK21)
$env:JAVA_HOME = '<jdk path>'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$env:GRADLE_USER_HOME = 'D:\dev\MCmod\zstdnet-build\cache\gradle-user'
$gradle = 'D:\dev\MCmod\zstdnet-build\tools\gradle-8.8\bin\gradle.bat'
Push-Location mods\<mc>\zstdnet-<loader>
& $gradle --project-cache-dir "D:\dev\MCmod\zstdnet-build\cache\project-cache\zstdnet-<loader>-<mc>" --console=plain build
Pop-Location
```

`build` runs `compileJava` + `compileTestJava` + `test` + the jar/jarJar (Forge/NeoForge) or
`remapJar` (Fabric). A green `BUILD SUCCESSFUL` means the variant compiles, the shared `mods/common`
core + your `Platform` impl link correctly, the shared tests pass, and the final jar (with bundled
`zstd-jni`) is produced under `../zstdnet-build/mods/<mc>/zstdnet-<loader>/libs/`.

**Sanity checks if it fails:**
- `cannot find symbol` for a core class → the `sourceSets` common `srcDir` is missing/mis-pathed (§4).
- `Platform not initialized` / NPE in config path → you forgot `Platforms.set(...)` (§6).
- duplicate-resource / duplicate-class → a copied variant still has its own `zstdnet/android/*.so`,
  `lang/*.json`, or a core `.java` that should only be in `mods/common` (§2).
- mixin/coremod apply failure → vanilla target signature changed for this MC version (§7).

---

## 9. Wire it into the helper scripts (optional)

If you want one-command builds, mirror the new variant in:
- `build-forge.ps1` / `build-neoforge.ps1` (or add a `build-fabric.ps1`),
- `scripts/run-regression.ps1` (it builds "all variants").

---

## Appendix — shared vs per-variant (quick reference)

**Shared, in `mods/common` (never duplicate):** `core/**`, `proxy/LocalZstdNet`,
`proxy/AndroidZstdNativeLoader`, `server/ServerProxyRuntime`, `server/ServerProxyConfigFile`,
`server/DedicatedServerAutoPort`, `ZstdServerList`, `platform/{Platform,Platforms,DefaultPlatform}`,
`assets/zstdnet/lang/*`, `zstdnet/android/**`, the unit tests.

**Per-variant (the thin layer you actually write):** `Zstdnet`, `ClientConfig`,
`client/ClientProxyPublisher`, `network/LanCompressionSync`, `server/ServerProxyBootstrap`,
`coremod/*Hooks`, `platform/<Loader>Platform`, (Fabric) `client/ZstdnetClient` + `mixin/*`, the
mod metadata + mixins.json/coremods.json + JS coremods, `build.gradle` / `settings.gradle` /
`gradle.properties`.
