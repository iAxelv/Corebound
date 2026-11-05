# Corebound

A Paper plugin for Corebound's island-world gameplay.

## Requirements

- JDK 25. Gradle can download the required Java toolchain if it is not already
  installed.
- Use `.\gradlew.bat` on Windows or `./gradlew` on macOS and Linux.

## Build and test

```shell
./gradlew test
```

On Windows, run `.\gradlew.bat test`.

Build a beta JAR locally with an incremented version number:

```powershell
.\gradlew.bat test jar "-PpluginVersion=0.1.0-beta.1"
```

The JAR is written to `build/libs/`. Increment the beta number for each build.

## Run a local Paper server

From the project directory, run:

```powershell
.\gradlew.bat runServer
```

The `runServer` task downloads and starts Paper 26.2 in the project's `run/`
directory, installs the plugin, and downloads FastAsyncWorldEdit (FAWE) 2.16.2
and PlaceholderAPI.
If prompted, accept the Minecraft EULA in `run/eula.txt` before starting the
server. FAWE provides the WorldEdit API and is required to load `.schem` files.

The default structure is `default.schem` in
`run/plugins/Corebound/schematics/`. Minecraft structure `.nbt` files are also
supported through Paper's built-in `StructureManager`.

Schematic files are loaded and pasted asynchronously through FAWE so large
creations do not block the server's main tick. Paste duration still depends on
schematic volume and the server's available CPU and storage performance.
Island data changes are applied in memory immediately and persisted from
versioned snapshots on Paper's async scheduler. Island-location checks use the
square-spiral grid index instead of scanning every island, while personal
weather and time are refreshed only on island entry or settings changes.

## Current features

- Void island world and square-spiral island allocation
- Schematic and `.nbt` structure placement
- Per-player world borders and island protection
- `/is create`, `/is home`, `/is visit`, `/is coop`, `/is open`, `/is close`,
  `/is leave`, `/is chat`, and staff-only `/is bypass`
- Staff-only `/core reload` to apply edited Corebound configuration and
  language YAML files without restarting the server. Persistent `data/*.yml`
  files are intentionally not reloaded; changing the island world name still
  requires a restart.
- The default language is English (`lang: EN_en`) in
  `plugins/Corebound/config.yml`. Set `lang: ES_es` to use Spanish, then run
  `/core reload` to apply the language change in-game.
- Optional PlaceholderAPI integration. For the player passed to PlaceholderAPI,
  `%thecore_island_name%` returns their island name (empty if they have no
  island), `%thecore_core_energy%` returns stored Core energy,
  `%thecore_island_energy_collected%` returns the island's accumulated energy,
  and `%thecore_core_hits%` returns Core hits by its owner and members. Energy
  values use compact formatting (for example, `1,2K`). Install PlaceholderAPI
  on production servers to use them; nametag/tab plugins should resolve the
  placeholders for the displayed player.
- Island invitations/membership (`invite`, `accept`, `members`), coop access,
  kick/ban/unban, and owner-confirmed island deletion using a native dialog
- Owner transfer, member ranks, rename, custom home spawn, and rank-based
  permission menus with per-member overrides
- Configurable settings, member, and permission menus in `plugins/Corebound/is/`, with
  ampersand-colored translations in `plugins/Corebound/lang/EN_en.yml` and
  `plugins/Corebound/lang/ES_es.yml`
- Per-island combat, damage, weather/time visuals, explosions, fluids,
  redstone, mob and spawner controls
- Island Core with persistent animation, combo-reactive scaling, hit feedback,
  combo actionbar, and an upgrade menu; configured in `plugins/Corebound/core/`.
  The Astral Fracture Tree lets island owners spend rebirth fragments on
  permanent Core-hit and pylon-production bonuses; its menu is configured in
  `plugins/Corebound/core/astral_tree.yml`.
  Level 3 unlocks purchasable extraction pylons with persistent, periodic
  energy pulses. Pylons require Core level 5 for Tier II and level 10 for Tier
  III by default, configurable in `plugins/Corebound/core/pylons.yml`. Core
  energy capacity starts at 500 and scales with Core level and Storage upgrades.
  The Upgrades menu improves manual combo ceiling, combo duration, or storage
  capacity; those levels persist when the Core is picked up and placed again.
  The Core's pylon capacity applies to both placed pylons and forged items
  awaiting placement. Pylons can be fitted with up to three
  elemental cells, and recovered with their tier and cells intact. The
  Core's level-3 Elemental Forge offers three random Fire, Lightning, or Void
  cells (duplicates are possible); each offer can be purchased once, and the
  list can be rerolled for a configurable energy cost. Cell lore describes
  installation and effects, while matching cells fuse up to Tier V. The owner
  can sneak-right-click the Core to pick it up, preserving its level and stored
  energy.
- Closed islands deny visits unless the visitor has enabled bypass. Old island
  records remain open by default.
- Island protection covers the full square island border. Grant `thecore.*` to
  staff who should bypass protection and visit closed islands.

Prestige and the Void talent tree are not implemented yet.
