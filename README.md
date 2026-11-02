# TheCore

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
directory, installs the plugin, and downloads WorldEdit 7.4.6. If prompted,
accept the Minecraft EULA in `run/eula.txt` before starting the server.
WorldEdit is required to load `.schem` files.

The default structure is `clipboard.schem` in
`run/plugins/TheCore/schematics/`. Minecraft structure `.nbt` files are also
supported through Paper's built-in `StructureManager`.

## Current features

- Void island world and square-spiral island allocation
- Schematic and `.nbt` structure placement
- Per-player world borders and island protection
- `/core create`, `/core home`, `/core visit`, and `/core trust`

Incremental energy, cells, pylons, and prestige are not implemented yet.
