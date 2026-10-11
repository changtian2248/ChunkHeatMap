## 1.0.2

### Fixed
- **JourneyMap: heatmap still offset / the player not at the centre.** JourneyMap maps blocks to
  pixels in **physical (window) pixels** — `MapRenderer.getBlockPixelInGrid` uses
  `Window.getScreenWidth()/getScreenHeight()` — while this mod used `getGuiScaledWidth()/Height()`
  (**GUI-scaled** pixels). The two differ by
  `scaleFactor = JmUI.calculateScaleFactor() = guiScale × getScreenWidth()/getWidth()`.
  - The block↔pixel mapping now uses the physical-pixel basis, anchored on JourneyMap's own
    `getBlockPixelInGrid(0, 0)`.
  - The hover tooltip converts the GUI mouse position with the same `scaleFactor`.
- **JourneyMap: overview / source-list UI mis-scaled and clipped.** That UI is now drawn inside
  `poseStack.scale(scaleFactor)` (it lives in GUI-scaled space).

## 1.0.1

### Fixed
- **JourneyMap: heatmap was offset from the map.** The world→screen mapping used
  `UIState.blockBounds.min` as the visible-area origin, but JourneyMap expands `blockBounds` outward
  by 32 blocks when building it (`updateUIState`), so every heat cell was shifted by
  `32 × blockSize` pixels. The tooltip used the same wrong formula, so its chunk coordinates were
  wrong too.
  - Now uses JourneyMap's own authoritative mapping (`MapRenderer.getBlockPixelInGrid`):
    screen centre + `blockSize = zoom / 512`, with the exact centre read from the renderer.
  - The tooltip uses the inverse of the same formula, so it always agrees with the drawn cells.
- **JourneyMap: overview / source-list panels were pushed off-screen.** `UIState.displayBounds` is
  `(-32, -32, w + 64, h + 64)` when there is no viewport, so using its origin as the panel anchor
  clipped the panels. Panels are now pinned to the screen's top-left corner and clamped on-screen.

## 1.0.0

### Added
- Initial release.
- Per-chunk MSPT heatmap with seven tick categories, load-source lookup, scan overview, full source
  list, optional server-side MSPT monitoring, and click-to-locate.
- **Xaero's World Map** backend (ported from [MsptMap](https://github.com/Drizzle379/MsptMap) by
  Drizzle).
- **JourneyMap** backend (new), using JourneyMap's official v2 plugin API.
- Dual-map support: install either, both, or neither map mod — only Fabric API is required.
