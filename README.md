# Client Mod Extractor


## TL;DR
This tool scans through a folder full of `.jar` files, identifies which ones are safe for the server, and safely copies them into a `ServerMods` folder so you can drag-and-drop them right onto your server.

## The Problem I'm Fixing
If you are a Minecraft server owner, you probably know the pain of downloading or creating a big modpack to put on your server, only to realize that half of the mods in the folder are "Client-Side Only" (mods that only change things on your screen, like menus or sounds). If you drop those client-side mods onto your server, your server will either **instantly crash**, or **crash later** when the client-only code tries to run on the server.

## Features
- **Cross-Platform:** Works on Windows, Mac, and Linux out-of-the-box (requires Java to be installed, which you already have if you play Minecraft).
- **Smart Cloud Library:** Automatically connects to GitHub to check massive lists of known "problematic" mods that have mislabeled themselves, falsely claiming to be server-safe.
- **Offline Mode:** If you don't have internet access, falls back to a local list of exclusions.

---

## How to Use

1. Download the `ModExtractor.java` file + your specific launcher script:
   - For Windows → `Run-Extractor-Windows.bat`
   - For Linux → `Run-Extractor-Linux.sh`
2. Drop both files into your `mods` folder where all your `.jar` files are located.
3. Double-click the launcher script.
4. Wait for the script to finish running.
5. Open the newly created `ServerMods` folder and grab your safe, server-ready mods!
6. Delete or move the `ServerMods` folder out of your `mods` folder.

---

## Technical Breakdown

Because Minecraft modding has multiple ecosystems, the script uses different methodologies to identify client-only mods depending on the modloader. 

The script reads the `.jar` files as standard ZIP archives and analyzes their metadata files:

### Fabric
1. Locates `fabric.mod.json`.
2. Parses the JSON structure.
3. Identifies the mod as Client-Only if it contains `"environment": "client"`.

### NeoForge & Forge
1. Locates `META-INF/neoforge.mods.toml` (for NeoForge) or `META-INF/mods.toml` (for Forge).
2. **Dependency Striping:** To prevent false positives, the script intelligently slices the TOML file and ignores everything after the first `[[dependencies` or `[[mixins` block. This ensures that if a mod *depends* on a client-side mod (like `flywheel`), it isn't incorrectly flagged as client-only itself.
3. Checks the core mod properties for any of the following tags:
   - `clientSideOnly = true`
   - `side = "CLIENT"`
   - `displayTest = "IGNORE_SERVER_VERSION"`

### Dynamic Cloud Exclusions
The script reaches out via standard HTTP GET requests to fetch community-maintained blocklists (such as the `itzg` docker-minecraft-server JSON lists) and my own custom exclusion list. It extracts the JSON arrays using regex and builds a unified Hash Set of blocked mod IDs. Any mod that matches these IDs is skipped, even if its internal metadata claims it is server-safe.
