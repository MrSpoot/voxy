# Voxy save format

The current save format version is **1**. The authoritative server is the only component that reads or writes it; client-side replica worlds and benchmarks remain ephemeral.

## Data directories

- Windows: `%APPDATA%\Voxy`
- Linux: `${XDG_DATA_HOME:-~/.local/share}/voxy`
- macOS: `~/Library/Application Support/Voxy`
- Override: `--data-dir=<path>` or `-Dvoxy.dataDir=<path>`

Profiles live under `profiles/<profile-key>/profile.json`. Worlds live under `worlds/<world-key>/`. Keys are limited to 1–64 ASCII letters, digits, `.`, `_` and `-`, and cannot escape the data directory.

## Transaction model

Each world contains:

```text
HEAD
HEAD.bak
manifests/<generation>-<sha256>.json
objects/chunks/<sha256>.vxc
objects/players/<sha256>.json
```

Chunk and player objects are immutable and addressed by their SHA-256 digest. A manifest contains the world UUID, display name, seed, creation/open dates, complete generation settings, height, simulation/render distances, autosave interval, object references and reserved versioned sections for time, entities and fluids.

A save writes and flushes new objects, writes the immutable manifest, then atomically replaces `HEAD`. `HEAD.bak` retains the previous pointer for interrupted non-atomic filesystem fallbacks. An interruption before the final pointer replacement leaves the previous complete generation active. Hash or schema failures are reported and the affected files are never overwritten automatically.

## Chunk and player data

Chunk objects contain only block edits that differ from deterministic procedural generation. They store stable namespaced block IDs rather than runtime numeric IDs. Returning a block to its generated value removes the delta.

Player objects are keyed by the profile UUID sent in protocol version 5 and contain position, rotation, vertical velocity, grounded/noclip flags, hotbar stable IDs and selected slot.

## Compatibility and migration

Every profile, manifest, chunk and player object has a format version. Newer versions are refused. Older manifests require a registered adjacent `WorldSaveMigrator`; without one, loading fails as incompatible. A future migration must publish a new immutable generation and must not modify the source generation in place.
