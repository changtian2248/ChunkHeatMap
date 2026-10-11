# ChunkHeatMap

**Per-chunk MSPT heatmap for Minecraft (Fabric 1.21.1).** Draws on **Xaero's World Map** or
**JourneyMap** — whichever you have installed. Only **Fabric API** is required.

**逐区块 MSPT 热力图**，同时支持 **Xaero 的世界地图** 与 **旅行地图（JourneyMap）**。装了哪个就在哪个上显示，
两个都装就两个都画。硬依赖只有 **Fabric API**。

---

## ⚠️ Credits / 来源说明

This project is a **port and extension** of **[MsptMap](https://github.com/Drizzle379/MsptMap)** by
**[Drizzle](https://github.com/Drizzle379)** (MIT License).

- The **server-side sampling, networking, commands, and client data layer** are **ported from
  MsptMap's source code** (with the Stonecutter multi-version conditionals resolved for 1.21.1).
- The **Xaero's World Map rendering backend** is likewise **ported from MsptMap** — the Mixin that
  injects into Xaero's `GuiMap` and the heatmap drawing logic are the original author's work.
- The **JourneyMap backend** (official v2 plugin API integration) is **newly written** for this
  project.

**All credit for the original design and the Xaero implementation goes to Drizzle.** This repository
exists to add JourneyMap support on top of that work. If you like this mod, please also star the
original: <https://github.com/Drizzle379/MsptMap>

> 本项目是 **[Drizzle](https://github.com/Drizzle379)** 的 **[MsptMap](https://github.com/Drizzle379/MsptMap)**
> （MIT 许可）的**移植与扩展**：
> - **服务端采样、网络通信、命令、客户端数据层**均**移植自 MsptMap 源码**（已把 Stonecutter 的多版本条件注释解析为 1.21.1）；
> - **Xaero 世界地图的渲染后端**同样**移植自 MsptMap**——注入 Xaero `GuiMap` 的 Mixin 与热力图绘制逻辑均为原作者所写；
> - **旅行地图后端**（官方 v2 插件 API 集成）为本项目**新写**。
>
> **原设计与 Xaero 实现的全部功劳归 Drizzle。** 本仓库只是在其基础上增加了旅行地图支持。
> 如果你喜欢这个模组，也请去给原项目点个 star：<https://github.com/Drizzle379/MsptMap>

---

## Features / 功能

| | |
| :--- | :--- |
| 🌡️ **Per-chunk heatmap** | Chunks are coloured green → yellow → red by the tick time they consume. Weakly-loaded chunks that did no measurable work stay pale grey. |
| 🧩 **Seven tick categories** | Random ticks, scheduled ticks, block updates, block events, block entities, entities and mob spawning are measured independently; the tooltip shows the breakdown. |
| 🏷️ **Load source lookup** | Resolves the load ticket behind each chunk (`player_loading`, `forced`, `portal`, `ender_pearl`, `dragon`, …). Chunks holding a ticket are outlined in blue. |
| 📊 **Scan overview** | Total mspt, entity count, load sources and the five costliest chunks, aggregated across all dimensions. Click a row to locate it. |
| 📋 **Full source list** | Expands the complete load-source list next to the overview. Scroll with the wheel, or use the ▲ / ▼ paging buttons. |
| 📡 **Optional monitoring** | Off by default. When enabled, the server watches MSPT and, on sustained overload, scans on its own and alerts online operators with clickable chunk links. |
| 🖱️ **Click to locate** | Clicking a row in the overview or source list moves the map to that chunk. |
| 🗺️ **Dual map support** | Works on Xaero's World Map **and** JourneyMap. Install either, both, or neither — the game starts fine regardless. |

## Requirements / 环境要求

| Dependency | Required | Notes |
| :--- | :--- | :--- |
| **Minecraft** | Yes | 1.21.1 |
| **Fabric Loader** | Yes | ≥ 0.16 |
| **Fabric API** | Yes | 1.21.1 |
| [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) | Optional | Client only. Install to draw the heatmap on Xaero's map. |
| [JourneyMap](https://modrinth.com/mod/journeymap) | Optional | Client only. Install to draw the heatmap on JourneyMap. |
| [Mod Menu](https://modrinth.com/mod/modmenu) | Recommended | Adds a settings entry. |

> **Neither map mod is required.** ChunkHeat only hard-depends on Fabric API. If neither map mod is
> installed, the game starts normally and simply has nowhere to draw the heatmap.

## Installation / 安装

1. Install **Fabric Loader** (≥ 0.16) and **Fabric API** for 1.21.1.
2. Drop `ChunkHeat-1.0.1.jar` into your `mods/` folder.
3. (Optional) Install **Xaero's World Map** and/or **JourneyMap** to see the heatmap.
4. (Recommended) Install the same jar on the **server** — sampling, the `/chunkheat` command, scan
   permissions and the MSPT monitor all need the server side. A client-only install will report
   "the server does not have ChunkHeat installed" when you press Scan.

## Usage / 用法

- **In the map UI**: buttons for **Scan / Clear / Config**, plus a toggle for the scan overview.
  - On Xaero: a column of buttons on the left edge.
  - On JourneyMap: a group of buttons added to the fullscreen map's toolbar.
- **Commands**: `/chunkheat scan [seconds]`, `/chunkheat access ops|all`,
  `/chunkheat monitor on|off|threshold|cooldown|audience`.
- **Singleplayer note**: while the map screen is open the world is paused, so sampling only starts
  after you close the map. The button tooltip says so.

## How it works / 原理

- **Server side** measures the tick time each chunk consumes, split into seven categories, over an
  N-second window, then sends a per-dimension snapshot to the client.
- **Client side** stores the snapshot and draws it on the map.
  - **Xaero backend**: a Mixin injects into Xaero's `GuiMap` render pass and fills the heatmap into
    its vertex buffer (ported from MsptMap).
  - **JourneyMap backend**: uses JourneyMap's official **v2 plugin API** — the heatmap is drawn in
    `FullscreenRenderEvent` using JourneyMap's own block↔pixel space (its `MapRenderer.getBlockPixelInGrid`
    works in **physical/window pixels** via `Window.getScreenWidth()`, not GUI-scaled pixels), buttons come
    from `AddonButtonDisplayEvent`, and clicks from `FullscreenMapClickEvent`.

## Known limitations / 已知限制

- Only the **fullscreen map** is drawn (the minimap is intentionally left alone).
- On JourneyMap, the overview / source-list panels are pinned to the **top-left of the screen**
  (JourneyMap lays out its own buttons, so the panels cannot hug the button column the way they do
  on Xaero).
- JourneyMap's API has **no scroll event**, so the source-list wheel is implemented with a small
  Mixin into JourneyMap's fullscreen screen (`chunkheat.jm.mixins.json`, `required: false`). Paging
  buttons are provided as well, so the feature still works if that Mixin ever stops applying.

## Building / 构建

```bash
./gradlew build          # output: build/libs/ChunkHeat-<version>.jar
```

Toolchain: Gradle 9.5.1 + Fabric Loom 1.17.21, Java 21, official Mojang mappings.
See `gradle.properties` for dependency versions.

## License / 许可

MIT — see [LICENSE](LICENSE). Original work © Drizzle (MsptMap); port and JourneyMap support
© changtian2248.
