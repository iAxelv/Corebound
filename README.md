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

## Run a local Paper server

From the project directory, run:

```powershell
.\gradlew.bat runServer
```

The `runServer` task downloads and starts Paper 26.2 in the project's `run/`
directory, installs the plugin, and downloads FastAsyncWorldEdit (FAWE) 2.16.2.
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
- Island invitations/membership (`invite`, `accept`, `members`), coop access,
  kick/ban/unban, and owner-confirmed island deletion using a native dialog
- Owner transfer, member ranks, rename, custom home spawn, and rank-based
  permission menus with per-member overrides
- Configurable settings, member, and permission menus in `plugins/Corebound/is/`, with
  ampersand-colored translations in `plugins/Corebound/lang/messages_es.yml`
- Per-island combat, damage, weather/time visuals, explosions, fluids,
  redstone, mob and spawner controls
- Island Core with persistent animation, combo-reactive scaling, hit feedback,
  combo actionbar, and an upgrade menu; configured in `plugins/Corebound/core/`.
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
