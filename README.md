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
`run/plugins/TheCore/schematics/`. Minecraft structure `.nbt` files are also
supported through Paper's built-in `StructureManager`.

Schematic files are loaded and pasted asynchronously through FAWE so large
creations do not block the server's main tick. Paste duration still depends on
schematic volume and the server's available CPU and storage performance.

## Current features

- Void island world and square-spiral island allocation
- Schematic and `.nbt` structure placement
- Per-player world borders and island protection
- `/is create`, `/is home`, `/is visit`, `/is coop`, `/is open`, `/is close`,
  and staff-only `/is bypass`
- Island invitations/membership (`invite`, `accept`, `members`), coop access,
  kick/ban/unban, and owner-confirmed island deletion
- Configurable settings and member menus in `plugins/TheCore/is/`, with
  ampersand-colored translations in `plugins/TheCore/lang/messages_es.yml`
- Per-island combat, damage, weather/time visuals, explosions, fluids,
  redstone, mob and spawner controls
- Closed islands deny visits unless the visitor has enabled bypass. Old island
  records remain open by default.
- Island protection covers the full square island border. Grant `thecore.*` to
  staff who should bypass protection and visit closed islands.

Incremental energy, cells, pylons, and prestige are not implemented yet.
