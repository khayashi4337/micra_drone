# Micra Drone

Minecraft (NeoForge) mod for programming education. Players write code in a
custom Python-like language to control a farming drone, automate crop work,
and unlock progression — inspired by
[The Farmer Was Replaced](https://store.steampowered.com/app/2060160/The_Farmer_Was_Replaced/).

Status: early scaffold (Phase 0). See project plan for the phased roadmap.

## Requirements

- Java 21 (JDK)
- Minecraft 1.21.1 / NeoForge 21.1.238

## Development

```
./gradlew runClient
```

First run downloads and decompiles Minecraft; this can take a while.

## License

Mod code: All Rights Reserved (see `gradle.properties` / `neoforge.mods.toml`).
Template boilerplate under `TEMPLATE_LICENSE.txt` is MIT-licensed by the
NeoForged project.
