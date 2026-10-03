# Third-party notices

## Kyant0 / AndroidLiquidGlass (Backdrop)

Cyclone Mobile uses the Backdrop renderer from [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) and adapts the project's published `LiquidButton`, `InteractiveHighlight`, and `DragGestureInspector` sample code for its shared button layer.

- Upstream source tag used for the integration: `1.0.0`
- Maven artifact: `io.github.kyant0:backdrop:1.0.0`
- Companion capsule artifact used by the upstream sample: `io.github.kyant0:capsule:2.1.1`
- License: Apache License 2.0
- License text: https://www.apache.org/licenses/LICENSE-2.0

The adapted Cyclone source keeps the upstream rendering recipe and attribution comments adjacent to the implementation. Kyant 1.0.0 was selected because its Android LiquidButton implementation has the same core visual recipe used by the later catalog—vibrancy, 2dp blur, 12/24dp lens refraction, interactive deformation, tint and surface-color passes—without requiring Cyclone 4.4.2 to migrate to the later AGP 9 / Compose 1.12 toolchain.
