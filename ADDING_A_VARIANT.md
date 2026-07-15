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
> instead of `jarJar`/`include` **and relocated** to `cn.tohsaka.factory.zstdnet.shaded.com.github.luben.zstd`
> (so it can't clash with a host-provided zstd-jni, e.g. on Mohist); and the source set **excludes** `server/DedicatedServerAutoPort.java`
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
mods/1.18.2/zstdnet-forge        Forge 1.18.2      JDK 17   coremods
mods/1.19.2/zstdnet-forge        Forge 1.19.2      JDK 17   coremods
mods/1.19.3/zstdnet-fabric       Fabric 1.19.3     JDK 17   mixins   (pre-1.19.4 GUI; see §12)
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

---

## 10. Minecraft 26.1+ (the un-obfuscated era) — what's different

26.1 (the first 2026 "year.drop.hotfix" drop, successor to 1.21.x) is **Minecraft's first
un-obfuscated release** and needs a different toolchain. The `mods/26.1/*` variants are the
reference. One 26.1 variant covers the whole `26.1.x` line (26.1.1 / 26.1.2).

**Toolchain (new):**
- **JDK 25** required (was 17/21). On this machine: `C:\Users\78569\.jdks\liberica-25.0.3`.
- **Gradle 9.x** required (NeoForge ≥9.1, Fabric Loom 1.16 → 9.4.1). A `gradle-9.4.1` dist lives at
  `../zstdnet-build/tools/gradle-9.4.1`. `build-neoforge.ps1` / `build-fabric.ps1` pick gradle+JDK
  per `-MinecraftVersion`; `scripts/run-regression.ps1` sets a per-target `gradleBat`.
- **Gradle 9 quirk:** add `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` or `:test`
  fails with "Failed to load JUnit Platform" (Gradle 9 no longer adds the launcher automatically).

**Pinned versions (26.1.2):** `neo_version=26.1.2.76`, ModDevGradle `2.0.141`; Fabric
`loader_version=0.19.2`, `fabric_api_version=0.151.0+26.1.2`, `loom_version=1.16.3`; zstd-jni `1.5.7-7`.

**NeoForge build.gradle:** toolchain 25; `neoforge.mods.toml` → `javaVersion="[25,)"`, minecraft
`versionRange="[26.1,26.2)"`. zstd-jni is bundled+relocated via `com.gradleup.shadow` (Gradle-9
compatible **9.x**, not the 8.3.x used on Gradle 8 variants), **not** `jarJar` — `shadowJar` flattens
it into the mod jar, relocates `com.github.luben.zstd` → `cn.tohsaka.factory.zstdnet.shaded.…`, and
`exclude 'module-info.class'` drops the nested module so it can't collide with a host zstd-jni (Mohist).
ModDevGradle **removed `additionalRuntimeClasspath`** — keep `implementation 'com.github.luben:zstd-jni:1.5.7-7'`
(it's on the moddev dev-run/test classpath with the original package) plus a `shadowBundle` config holding
the same coordinate as the only thing shadow merges.

**Fabric build.gradle (un-obfuscated Loom):** apply via `plugins { id 'net.fabricmc.fabric-loom'
version "${loom_version}" }` (resolved from the fabric maven in `settings.gradle` pluginManagement).
**No `mappings` line** (names are official already); use `implementation` (not `modImplementation`);
no `remapJar`. `zstdnet.mixins.json` → `compatibilityLevel "JAVA_25"`; `fabric.mod.json` →
`"java": ">=25"`, `"minecraft": ">=26.1 <26.2"`.

**Vanilla API rename cheat-sheet (1.21.1 → 26.1)** — affects the per-variant layer only
(`mods/common` is API-light and unchanged). Always confirm with `javap` against the real jar
(`.../caches/fabric-loom/minecraftMaven/.../minecraft-merged-deobf-26.1.2.jar`):
- `net.minecraft.resources.ResourceLocation` → `…resources.Identifier` (`fromNamespaceAndPath` same)
- `GuiGraphics` → `GuiGraphicsExtractor`; `drawString`→`text`, `drawCenteredString`→`centeredText`;
  `blit` now needs a `RenderPipeline`; `Screen.render(...)` → `extractRenderState(GuiGraphicsExtractor,…)`
- `ClickEvent`/`HoverEvent` are interfaces → `new ClickEvent.CopyToClipboard(String)`,
  `new HoverEvent.ShowText(Component)`, etc.
- `Player.hasPermissions(int)` → `permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)` (lvl 2)
- `Button.onPress()` → `onPress(InputWithModifiers)` (synthesize `new KeyEvent(KEY_RETURN,0,0)`)
- screen input: `keyPressed(int,int,int)`→`keyPressed(KeyEvent)`, `mouseClicked(double,double,int)`→
  `mouseClicked(MouseButtonEvent, boolean)`; `CommonInputs` removed (use `InputWithModifiers.isSelection()`)
- `NetworkServerEntry.getServerData()` removed → protected `serverData` field (Fabric `@Accessor`
  mixin; NeoForge reflection)
- `GameProfile.getName()` → `name()` (authlib bumped to a record)

**Fabric API moves (26.1):** `ClientCommandManager` → `ClientCommands` (static `literal`/`argument`);
`HudRenderCallback` **removed** → `HudElementRegistry.addLast(Identifier, (gui, deltaTracker) -> …)`;
`PayloadTypeRegistry.playS2C()/playC2S()` → `clientboundPlay()/serverboundPlay()`.

**NeoForge networking (26.1):** `PacketDistributor.sendToServer(...)` (in-handler) → `context.reply(...)`.

---

## 11. Minecraft 1.18.2 (pre-1.19) — back-port deltas

Going **older** than 1.19.2 (e.g. the Forge 1.18.2 variant) crosses two 1.19 API breaks plus a
handful of pre-1.19 renames. Toolchain is unchanged from 1.19.2 (**JDK 17, Gradle 8.8, ForgeGradle
`[6.0.24,6.2)`** — confirmed: FG6 sets up 1.18.2 fine). `gradle.properties` → `forge_version=40.3.x`,
`mods.toml` → `loaderVersion="[40,)"` + minecraft `versionRange="[1.18.2,1.19)"`, `pack.mcmeta`
→ `"pack_format": 8`. The shared `mods/common` core compiles unchanged.

**Text / chat API (1.19 introduced the `Component` factories):**
- `Component.translatable(k, …)` → `new TranslatableComponent(k, …)`; `Component.literal(s)` →
  `new TextComponent(s)` (`net.minecraft.network.chat.{TranslatableComponent,TextComponent}`).
- `entity.sendSystemMessage(Component)` → `entity.sendMessage(Component, net.minecraft.Util.NIL_UUID)`.
- *Unchanged*: `ClickEvent`/`HoverEvent` are still concrete classes (became interfaces only in 26.1);
  `Connection.setupCompression(int, boolean)` already takes the 2-arg form in 1.18.2.

**Forge event renames (the 1.19 `ScreenEvent` re-nest + a few others):**
- `ScreenEvent.Init.Post` → `ScreenEvent.InitScreenEvent.Post` (`getListenersList()/addListener()/
  removeListener()` unchanged); `ScreenEvent.Render.Pre/Post` → `ScreenEvent.DrawScreenEvent.Pre/Post`
  (accessor is `getPoseStack()`); `ScreenEvent.KeyPressed.Pre` → `ScreenEvent.KeyboardKeyPressedEvent.Pre`;
  `ScreenEvent.MouseButtonPressed.Pre` → `ScreenEvent.MouseClickedEvent.Pre`.
- `ScreenEvent.Opening` → **top-level** `net.minecraftforge.client.event.ScreenOpenEvent` (only
  `getScreen()` = the screen being opened; no `getCurrentScreen()` — read the outgoing screen from the
  public field `Minecraft.getInstance().screen`, still valid at event time).
- `ScreenEvent.Closing` **does not exist** in 1.18.2 → drop the handler; screen-keyed `WeakHashMap`
  state self-clears on GC.
- `RenderGuiEvent.Post` → `RenderGameOverlayEvent.Post`; its PoseStack accessor is `getMatrixStack()`
  (the `getPoseStack()` rename landed in 1.19's `RenderGuiEvent`).
- `ClientPlayerNetworkEvent.LoggingIn/LoggingOut` → `LoggedInEvent/LoggedOutEvent`.
- *Unchanged*: `RegisterClientCommandsEvent`, `PlayerEvent.PlayerLoggedInEvent` (`getEntity()` works),
  `TickEvent.ServerTickEvent`, `ServerStartedEvent`/`ServerStoppingEvent`.

**Forge networking (`SimpleChannel.MessageBuilder`):** `.consumerMainThread(X::handle)` →
`.consumer(X::handle)` (1.18.2 has no `consumerMainThread`; the handlers already `enqueueWork(...)`
+ `setPacketHandled(true)` themselves, so the raw `.consumer` is a drop-in).

**Client server-list (`ServerList`):** no by-IP `get(String)` and no 2-arg `add(ServerData, boolean)`
in 1.18.2 → dedup by iterating `for (i<size()) get(int)` comparing `.ip`, and `add(ServerData)` 1-arg.

**Coremods:** no change. SRG ids (`m_6328_`/`m_7038_`/`m_139777_`/`m_130136_`) are stable across
1.18.2↔1.19.2↔1.20.1; `ConnectScreen.startConnecting` is the 4-arg form (the `isQuickPlay` boolean is
1.20+), matching the 1.19.2 descriptor. (`new ResourceLocation(String, String)` is merely deprecated
in 1.18.2 — a warning, not an error.)

---

## 12. Minecraft 1.19.3 (Fabric) — back-port deltas

Reference variant: `mods/1.19.3/zstdnet-fabric` (template = `mods/1.20.1/zstdnet-fabric`). 1.19.3 sits
**between two GUI-API generations** and differs subtly from *both* neighbors — don't blindly copy from
either:
- **vs 1.19.2:** 1.19.3 (22w42a) made `AbstractWidget.x/y` **private** and added
  `getX()/getY()/setX()/setY()`. So 1.19.2's public `.x`/`.y` field access does **not** compile on 1.19.3 —
  use the getters (same as 1.20.1). (The 1.19.2 *Forge* variant still uses the public fields; that's the trap.)
- **vs 1.20.1:** 1.19.3 still renders with **`PoseStack`** (not `GuiGraphics`, which is 1.20) and has **no**
  `Tooltip` / `EditBox.setHint` / `CommonInputs` (all part of the 1.19.4 widget rework).

Toolchain is the same as the other JDK-17 Fabric variants (**JDK 17, Gradle 8.8, Loom 1.7.4**).
`gradle.properties` → `loader_version=0.16.10`, `fabric_api_version=0.76.1+1.19.3`; `pack.mcmeta` →
`"pack_format": 12`; `fabric.mod.json` keeps `"java": ">=17"` + `minecraft "~1.19.3"`; `zstdnet.mixins.json`
stays `compatibilityLevel "JAVA_17"`. The whole `mods/common` core compiles unchanged.

**Only 3 files needed edits vs the 1.20.1 Fabric template; everything else copies verbatim:**
- **Rendering → `PoseStack`** (`client/ClientProxyPublisher`, `mixin/ShareToLanScreenMixin`):
  `GuiGraphics gui` → `PoseStack gui`; `gui.fill(...)` → `GuiComponent.fill(gui, ...)`;
  `gui.drawString(font, …)` → `font.drawShadow(gui, …)`. The `HudRenderCallback` callback and the
  `Screen.render` mixin take `PoseStack` (`render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V`).
- **No widget Tooltip/hint/CommonInputs** (those are 1.19.4): drop `EditBox.setHint` /
  `setTooltip(Tooltip.create(...))` / `setFocused`; replace `CommonInputs.selected(keyCode)` with
  `keyCode == 257 || keyCode == 335`. **Keep** `getX()/getY()/getWidth()/getHeight()` (all present in 1.19.3).
- **`ConnectScreen.startConnecting` is 4-arg** (no `quickPlay` — that's 1.20+): drop the trailing arg at
  both call sites in `ClientProxyPublisher` and in `ConnectScreenMixin`'s `@ModifyVariable method=`
  descriptor + handler signature.
- **`ServerGamePacketListenerImpl` has no `getRemoteAddress()`** in 1.19.3 (`server/ServerProxyBootstrap`):
  read it off the underlying `Connection` via the existing accessor —
  `((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection().getRemoteAddress()`.

*Unchanged / verbatim:* all server/network/auth/coremod-hook files; the accessors (`gameProfile` +
`connection` fields exist); `ConnectionMixin`, `ClientIntentionPacketMixin`, `DedicatedServerMixin`
(`initServer` STORE), `MinecraftServerMixin` (`getCompressionThreshold`),
`ServerHandshakePacketListenerImplMixin` (`handleIntention`), `PlayerTabOverlayMixin` (`render` redirect —
the `isEncrypted` TAB-head gate exists since 1.19.1). `HttpUtil.isPortAvailable` **is** available in 1.19.3
(it was added in 1.19.3; only the older 1.19.2 Forge variant has to work around its absence). The two
`@Mixin target … ContainerEventHandler/Screen … not found in environment type SERVER` WARNs on a dedicated
server are expected (client-only mixins listed in the common section) and harmless. Build (10-variant) green
+ shared unit tests pass; dedicated-server runtime startup verified (auto-takeover + threshold override apply).

## 13. Minecraft 1.16.5 (Forge) — the most expensive variant (Java-8 wall + cross-1.17 rename)

Reference variant: `mods/1.16.5/zstdnet-forge` (template = `mods/1.18.2/zstdnet-forge`). This is **not** a
3–5-file back-port like §11/§12 — it's the costliest variant in the repo, because **three walls stack**.
Build it with `build-forge-1.16.5.ps1` (its own script — the toolchain is fundamentally different).

**Wall 1 — Java 8 (decisive).** 1.16.5 runs Java 8 (class 52). The shared `mods/common` core therefore
**must be Java-8 source** (records → final classes, text blocks → concat, `var`/arrow-switch/instanceof-patterns
desugared, `String.isBlank`→`trim().isEmpty()`, `HttpClient`→`HttpURLConnection`, `List/Map/Set.of`→
`Arrays`/`Collections`, `Files.read/writeString`/`readAllBytes`/`String.repeat`/`BAOS.writeBytes`/`HexFormat`
all removed). That down-level is a **one-time, single-source** change: Java-8 source still compiles on
JDK 17/21/25, so the other ten variants are unaffected — **do not fork a Java-8 copy of common.** ★ Lesson:
keyword-grep misses Java 9+ usages; the only reliable gate is **compiling with a real JDK 8 `javac`**.

**Wall 2 — a second, older toolchain.** ForgeGradle **5.1.+** (legacy `buildscript{}` + `apply plugin`),
**Gradle 7.6.4**, **JDK 8 for the whole build** (FG5's ForgeFlower decompiler runs on the host JVM and crashes
on JDK 16+, so `toolchain.of(8)` can't save you — the daemon itself must be JDK 8). Put a `gradle-7.6.4`
dist at `zstdnet-build/tools/`. Two more 1.16.5-isms: **no `jarJar`** (FML added it in 1.18.2/Forge40), so
zstd-jni is bundled as an **embedded resource jar** (`embeddedZstd` config → `stageEmbeddedZstd` Copy task →
`cn/tohsaka/factory/zstdnet/embedded/zstd-jni.jar`) loaded at runtime through the `ZstdCodecs` reflective
façade (same isolation pattern as the Mohist fix — never relocate; relocation breaks JNI binding); and the
**`reobf { jar {} }`** step (FG5 reobfuscates the published jar back to SRG runtime names).

**Wall 3 — MCP-snapshot mappings ⇒ every class name differs.** Forge only adopted Mojang **class** names in
1.17; 1.16.5's `official` channel gives method/field names but **no class names**. So this variant uses
`mappings channel: 'snapshot', version: '20210309-1.16.5'` (MCP). Consequence: unlike every other variant
(which share official `net.minecraft.*` names and thus share `mods/common` verbatim), **1.16.5's thin layer
uses MCP class names**, and SRG ids are `func_*`/`field_*` (not `m_*`/`f_*`). The core map (1.18.2 → 1.16.5):
`Component`→`util.text.ITextComponent`, `TextComponent`→`StringTextComponent`, `TranslatableComponent`→
`TranslationTextComponent`, `ChatFormatting`→`util.text.TextFormatting`, `ClickEvent/HoverEvent`→
`util.text.event.*`, `Connection`→`network.NetworkManager`, `FriendlyByteBuf`→`network.PacketBuffer`
(`readUtf`/`writeUtf`→`readString`/`writeString`), `ResourceLocation`→`util.ResourceLocation`,
`ServerPlayer`→`entity.player.ServerPlayerEntity`, `player.connection`(`ServerGamePacketListenerImpl`)→
`network.play.ServerPlayNetHandler` (its `.connection` field → `.netManager`), `ClientIntentionPacket`→
`network.handshake.client.CHandshakePacket` (host in private field **`ip`**, read in `readPacketData`),
`ServerHandshakePacketListenerImpl`→`network.handshake.ServerHandshakeNetHandler` (field `networkManager`,
method `processHandshake`), `ConnectScreen`→`gui.screen.ConnectingScreen`, `EditBox`→`widget.TextFieldWidget`
(`getText`/`setText`/`setMaxStringLength`), `AbstractWidget`→`widget.Widget` (public `x`/`y` fields),
`GuiComponent`→`gui.AbstractGui`, `PlayerTabOverlay`→`gui.overlay.PlayerTabOverlayGui`. Methods: `getPort`→
`getServerPort`, `isPublished`→`getPublic`, `getMotd`→`getMOTD`, `usesAuthentication`/`setUsesAuthentication`→
`isServerInOnlineMode`/`setOnlineMode`, `isSingleplayer`→`isSinglePlayer`, `setupCompression(int,bool)`→
`setCompressionThreshold(int)`, `disconnect(Component)` on a raw NetworkManager → `closeChannel(ITextComponent)`,
`Util.NIL_UUID`→`util.Util.DUMMY_UUID`, `withStyle`→`modifyStyle`, rendering uses `blaze3d.matrix.MatrixStack`
(not PoseStack). Forge networking is under `net.minecraftforge.fml.network.*`; lifecycle events are
`fml.event.server.FMLServerStartedEvent/FMLServerStoppingEvent` + `fml.server.ServerLifecycleHooks`; GUI events
are `client.event.GuiScreenEvent.*`/`GuiOpenEvent`/`RenderGameOverlayEvent`/`ClientPlayerNetworkEvent`.

**Two structural deltas beyond pure renames:**
- **`server/DedicatedServerAutoPort` (common) is `exclude`d** (`sourceSets.main.java.exclude
  '**/server/DedicatedServerAutoPort.java'`, same as the Bukkit plugin) because it uses 1.17+ dedicated-server
  signatures (`DedicatedServerProperties` single-arg ctor; `serverPort`/`serverIp` fields). Its logic is rewritten
  in the thin layer as **`server/DedicatedAutoPortHooks`** (kept in the `server` package so it can reach the
  package-private `DedicatedAutoPortState`/`AutoPortPlan`). 1.16.5's `ServerProperties` ctor is 2-arg
  (`Properties, DynamicRegistries`) — reconstructing is painful — but it exposes `server-port`/`online-mode`/
  `network-compression-threshold` as **`public final` instance fields**, so the hook **mutates those final fields
  in place via reflection** (`setAccessible(true)+set` is legal on JDK 8 for non-static final instance fields) and
  updates the backing `Properties` map, returning the same instance. `ServerProxyBootstrap` then calls
  `DedicatedAutoPortState.activePlan()/.clear()` directly (not `DedicatedServerAutoPort.*`).
- **Connection-intercept coremod retargeted.** 1.16.5 has no static `ConnectScreen.startConnecting`; both
  `ConnectingScreen` constructors funnel into the private instance method **`connect(String ip, int port)`**
  (verified: the `(Screen,Minecraft,ServerData)` ctor parses `serverData.serverIP`→`ServerAddress`→`getIP()`→
  `connect`). The coremod injects at the head of `connect`, calls `ConnectScreenHooks.interceptConnect(String,int)`
  →`ServerAddress`, then writes host/port back to locals 1/2 via the helper methods `ConnectScreenHooks.hostOf`/
  `portOf` (so the coremod never has to emit a vanilla `getIP()/getPort()` whose SRG name is runtime-only —
  those calls live in compiled, reobf'd Java instead). The dedicated-auto-port coremod (folded into
  `zstdnet_lan_compression_threshold.js`) matches the `getProperties()` call inside `DedicatedServer.init()`
  (`func_71197_b ()Z`) by **return descriptor** `()Lnet/minecraft/server/dedicated/ServerProperties;` + following
  `ASTORE`, then injects `ALOAD0; SWAP; INVOKESTATIC DedicatedAutoPortHooks.prepareDedicatedServerProperties`.
  `getNetworkCompressionThreshold` is `func_175577_aI ()I`; the real-IP and tab-head coremods are the §11 ones with
  `Connection`→`NetworkManager`, `ClientIntentionPacket`→`CHandshakePacket`, `PacketBuffer.readString` = `func_150789_c`.

**Round-1 scope cuts (intentional):** premium verification is **off** (`ForgePlatform.supportsPremiumVerification()`
returns `false`; the two `PremiumAuth*Hooks` + `zstdnet_premium_auth.js` are deleted) — the `auth/*` common classes
still ship but stay dormant. 1.16.5 has **no `RegisterClientCommandsEvent`**, so `/zstdhud` + `/zstdport` register
via `RegisterCommandsEvent` (`net.minecraft.command.Commands`) — i.e. only on the integrated/LAN-host command
dispatcher, not when connected to a remote dedicated server (a known limitation; the HUD itself still renders via
`RenderGameOverlayEvent`).

**Status:** `build` green on JDK 8 (Gradle 7.6.4 + FG5) — `compileJava` + shared unit tests + `reobfJar` + dist jar
(`zstdnet-1.16.5-forge-<ver>.jar`, embedded zstd-jni, 4 coremods, reobf-to-SRG confirmed). No-regression: Forge
1.20.1 still builds green on JDK 17 (common's Java-8 down-level compiles unchanged there). Runtime not yet smoke-tested.

## 14. Minecraft 26.2 (NeoForge + Fabric) — 26.1 → 26.2 deltas (client-GUI rework)

Reference variants: `mods/26.2/zstdnet-neoforge` + `mods/26.2/zstdnet-fabric` (templates = the matching `mods/26.1/*`).
26.2 ("Chaos Cubed", 2026's second game drop) is content-focused but shipped a **client-GUI rework** that breaks the
thin per-variant layer. The **toolchain is identical to 26.1** (JDK 25, Gradle 9.4.1, Loom `1.16.3`, ModDevGradle
`2.0.141`) and `mods/common` compiles unchanged — only the loader-integration layer needed edits.

**Pinned versions (26.2):** `minecraft_version=26.2`; NeoForge `neo_version=26.2.0.15-beta` (the whole 26.2.x
NeoForge line is still **beta** — `<release>` on the maven metadata is still `26.1.2.x`; ModDevGradle resolves the
beta fine); Fabric `loader_version=0.19.3`, `fabric_api_version=0.154.2+26.2`, `loom_version=1.16.3` (1.16.3 handles
26.2 — Loom is version-independent in the un-obfuscated era). zstd-jni `1.5.7-7` unchanged. Metadata ranges:
`neoforge.mods.toml` minecraft `versionRange="[26.2,26.3)"`; `fabric.mod.json` `"minecraft": ">=26.2 <26.3"`;
`javaVersion`/`java` stay `25`; mixin `compatibilityLevel "JAVA_25"`. `pack.mcmeta` `pack_format` unchanged from 26.1.

**Vanilla API cheat-sheet (26.1 → 26.2)** — confirm with `javap` against the merged jar
(`.../caches/fabric-loom/minecraftMaven/.../minecraft-merged-deobf-26.2.jar`). All four affect the thin layer only:

| 26.1 | 26.2 | notes |
|---|---|---|
| `minecraft.screen` (field) | `minecraft.gui.screen()` | current screen moved off `Minecraft` onto `Minecraft.gui` (`net.minecraft.client.gui.Gui`); `Gui.screen()` reads it, `Minecraft.setScreenAndShow` / `Gui.setScreen` write it |
| `minecraft.options.hideGui` (field) | `minecraft.gui.hud.isHidden()` | F1 hide-HUD state moved to `Gui.hud` (`net.minecraft.client.gui.Hud`), `public boolean isHidden()` |
| `Connection.isEncrypted()` | *(removed)* — TAB-head gate is now `minecraft.getConnection().onlineMode()` (`ClientPacketListener#onlineMode`) | `PlayerTabOverlay#extractRenderState`'s "connection secure" gate was rewritten from `isEncrypted()` to `onlineMode()`. Retarget the redirect/coremod to `ClientPacketListener.onlineMode()Z`; the handler ORs in `LocalZstdNet.isLocalProxyEndpoint(listener.getConnection().getRemoteAddress())`. (`Connection` has no `isEncrypted`/`encrypted` flag anymore — `setEncryptionKey` just inserts Netty `CipherDecoder`/`CipherEncoder` handlers — so don't try to reconstruct `isEncrypted`.) |
| `net.minecraft.client.gui.screens.ShareToLanScreen` | `net.minecraft.client.gui.screens.MultiplayerOptionsScreen` | the "Open to LAN" screen was **renamed** (not deleted-as-feature): same `Screen` subclass, still has `EditBox portEdit`, the `lanServer.start` button + `lanServer.port` field, and calls `IntegratedServer.publishServer`. **Drift:** it overrides `extractBackground(GuiGraphicsExtractor,int,int,float)`, **not** `extractRenderState(...)` (that stays inherited from `Screen`), so a mixin's render `@Inject` must target `extractBackground` — `init()` (widget add) is unchanged. |

**Files edited (per variant; everything else copies verbatim from 26.1):**
- **Fabric:** `client/ClientProxyPublisher` (type `ShareToLanScreen`→`MultiplayerOptionsScreen` throughout, `minecraft.screen`→`minecraft.gui.screen()`, `options.hideGui`→`gui.hud.isHidden()`); `mixin/ShareToLanScreenMixin` (`@Mixin(MultiplayerOptionsScreen.class)`, render injects `extractRenderState`→`extractBackground`, casts); `mixin/ContainerEventHandlerMixin` + `mixin/ScreenMixin` (`instanceof` type swap); `mixin/PlayerTabOverlayMixin` (`@Redirect` retarget `Connection.isEncrypted()`→`ClientPacketListener.onlineMode()`). The mixin **class/file name `ShareToLanScreenMixin` is kept** (internal label) so `zstdnet.mixins.json` needs no change.
- **NeoForge:** `client/ClientProxyPublisher` (import + `instanceof MultiplayerOptionsScreen` + classname fallback `"multiplayeroptions"`, `options.hideGui`→`gui.hud.isHidden()`); `coremod/TabPlayerHeadHooks` (param `Connection`→`ClientPacketListener`, `onlineMode()`); `coremods/zstdnet_tab_player_head.js` (match owner `net/minecraft/client/multiplayer/ClientPacketListener` + name `onlineMode` + desc `()Z`; replacement hook descriptor `(L…/ClientPacketListener;)Z`). The other coremods (connect-intercept, lan-threshold, real-ip, premium-auth) were left as-is (their targets are core networking, not the reworked GUI).

**Status:** `build` green for both variants on JDK 25 (Gradle 9.4.1) — `compileJava` + shared unit tests + dist jars
(`zstdnet-26.2-fabric-<ver>.jar`, `zstdnet-26.2-neoforge-<ver>.jar`). No-regression: `mods/common` untouched, existing
variants unaffected (build scripts only gained additive `26.2` cases). **Runtime not yet smoke-tested** — in particular
the `MultiplayerOptionsScreen` mixin/coremod application and the LAN-port-field injection should be verified in-game
(the `lanServer.start`/`lanServer.port` text-matching degrades gracefully to "no field injected" if the layout drifted;
`/zstdport` remains the fallback for setting the port).
