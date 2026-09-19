# MeshImporter

Puts 3D models (`.obj`) into Minecraft 1.12.2 as real meshes. No voxels, no tiles, no block conversion: a model is
one file on the server, streamed to every client and drawn with its own geometry.

Author: Aleksei Usenko (arthaix). All rights reserved: you may use the released jar, but not modify or redistribute it (see LICENSE).

## What it does

- **Any size, flat cost.** A 2-million-triangle model is read, built and packed in a couple of seconds on the client.
  The world gets no tile entities and no chunk data; the server keeps a single small file per model.
- **Lit by the world.** Meshes use Minecraft's own lightmap: day and night, torches and lamps light them, surfaces are
  shaded by direction, and parts covered by other parts of the mesh (bridge undersides, eaves) stay in shadow.
- **Solid.** Players and mobs walk on meshes, bump into walls and stand on slopes; the server uses the same collision,
  so nobody gets pulled back. Blocks can be placed onto a mesh surface.
- **A look per material.** Every material of the model gets a block texture (tiled once per block, like real blocks),
  a flat colour, or the model's own texture, plus a tint, opacity and a glow switch.
- **Same placement as the block importer.** Scale, file up-axis, rotation around the anchor, mirror inside the bounding
  box, snap corner / lowest point / Blender origin, offsets in Blender axes. Material presets and recent files are
  remembered.
- **Preview before placing.** The finished mesh is shown where it will stand, with its bounding frame, before anything is
  sent to the server.

## Usage

1. Craft or grab the **Mesh Anchor** (Decorations tab) and place it: it is the origin of the model.
2. Right-click it. Choose the `.obj` with `...`, press **Scan**.
3. For each material choose the look: **Block** (click the block cell to pick one), **Color** or **Texture**. Click the
   swatch for colour and opacity. **Glow** for lamps and screens, **Skip** to leave a material out.
4. Set scale, axes, rotation, mirror, snapping and offset. **Build preview** to see it in the world.
5. **Place**. The model is uploaded once; changing only the position later does not upload it again.
6. **Remove** (or breaking the anchor) takes the model out of the world.

Placing and removing needs creative mode or op. The mod must be installed on the server and on every client.

## Commands

```
/meshimporter list          placed models (id, name, owner, anchor, triangles)
/meshimporter remove <id>   remove a model
/meshimporter tp <id>       teleport above a model
```

## Configuration (`config/meshimporter.cfg`)

| Key | Default | Meaning |
|---|---|---|
| `maxModelMegabytes` | 256 | largest model file a player may upload |
| `collision` | true | players and mobs collide with meshes |
| `placeOnMesh` | true | blocks can be placed onto mesh surfaces |
| `downloadPartsPerTick` | 4 | download speed to clients (parts of 256 KB per tick) |
| `renderDistance` | 512 | mesh parts farther than this are not drawn |
| `lightMillisPerFrame` | 2 | time per frame for refreshing world light on meshes |
| `selfShadow` | true | covered parts of a mesh get less sky light |
| `shadowLevel` | 4 | sky light under a covering part (0 dark … 15 no shadow) |
| `cellSize` | 64 | size of the culling and light cells in blocks |

## Good to know

- A mesh is not made of blocks: it does not block world light (grass under a bridge still gets sunlight), mobs do not
  path-find around it, and block-based maps (BlueMap, JourneyMap) do not show it.
- Model files live in `<world>/data/meshimporter/`, placed models in `<world>/data/meshimporter_instances.dat`.
  Clients cache downloaded models in `.minecraft/meshimporter/cache/`.

## Building

```
./gradlew build          # build/libs/meshimporter-<version>.jar
```

Requires JDK 8 for the game toolchain (Gradle itself runs on JDK 21, see `gradle.properties`). No other mods are needed.
