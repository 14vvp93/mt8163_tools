# MT8163 Tools Documentation

All documentation is maintained in English and describes the implementation on the `development` branch.

## Project

- [Project structure](PROJECT_STRUCTURE.md) — source layout, module boundaries, and Xposed routing.
- [Module lifecycle](architecture/dynamic-module-lifecycle.md) — process boundaries, enable/disable behavior, and hook ownership.

## Application modules

| Module | Documentation | Scope |
|---|---|---|
| Media | [README](modules/media/README.md) | ACC Snapshot, playback restoration, Toast notifications |
| Protection | [README](modules/protection/README.md) · [Firmware analysis](modules/protection/ANALYSIS.md) | Recents, Memory Cleaner, target firmware findings |
| Voltage | [README](modules/voltage/README.md) | MCU-reported battery voltage offset |
| Events | [README](modules/events/README.md) | Event submission, storage, history, and export |
| Autorun | No separate implementation document | UI placeholder only |

## Xposed integration

- [Xposed integration](xposed/README.md) — Legacy entry point, registry, process routing, and shared bootstrap.
- [Hook inventory](xposed/hooks/README.md) — active module hook packages and target processes.
- [Media hook](xposed/hooks/media/README.md)
- [Protection hook](xposed/hooks/protection/README.md)
- [Voltage hook](xposed/hooks/voltage/README.md)

## Documentation rules

- Keep project documentation under the repository-root `docs/` directory.
- Describe implemented behavior as implemented; mark device-dependent behavior as pending validation.
- Keep application-module behavior under `modules/`.
- Keep Xposed entry, routing, and hook implementation details under `xposed/`.
- Do not create hook documentation for modules that have no Xposed implementation.
- Keep firmware research separate from the user-facing module behavior.
