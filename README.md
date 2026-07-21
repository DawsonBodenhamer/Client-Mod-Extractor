# Client Mod Extractor

## TL;DR
This tool scans through a folder full of `.jar` files, identifies which ones are safe for the server, and safely copies them into a `Save_For_Server_Mods` folder so you can drag-and-drop them right onto your server.

## The Problem I'm Fixing
If you are a Minecraft server owner, you probably know the pain of downloading or creating a big modpack to put on your server, only to realize that half of the mods in the folder are "Client-Side Only" (mods that only change things on your screen, like menus or sounds). If you drop those client-side mods onto your server, your server will either **instantly crash**, or **crash later** when the client-only code tries to run on the server.

## Features
- **Cross-Platform:** Works on Windows, Mac, and Linux out-of-the-box (requires Java to be installed, which you already have if you play Minecraft).
- **Smart Cloud Library:** Automatically connects to GitHub to check massive lists of known "problematic" mods that have mislabeled themselves, falsely claiming to be server-safe.
- **Offline Mode:** If you don't have internet access, falls back to a local list of exclusions.

---

## How to Use

**Step 1: Download**
1. Locate the **Releases** section on the right side of this GitHub page.
2. Click on the latest version number (e.g., `v1.0.1`).
3. Scroll down to the **Assets** heading. (**Do not click "Source code" or "Assets"**)
4. Click `Client-Mod-Extractor.zip` to download it.

**Step 2: Setup**
1. Extract the downloaded `.zip` file.
2. Open your Minecraft `mods` folder where your `.jar` files are currently located.
3. Move the extracted files (`ClientModExtractor.java`, `Run-Extractor-Windows.bat`, and `Run-Extractor-Linux.sh`) directly into that `mods` folder.

*Note: Ignore the `ADVANCED_REVERSE_TOOL` folder unless you specifically know what it is for.*

**Step 3: Run**
1. Double-click the launcher script for your operating system:
   - **Windows:** `Run-Extractor-Windows.bat`
   - **Linux / Mac:** `Run-Extractor-Linux.sh`
   - *Note for Windows users: If Windows displays a "Security Warning" or "Windows protected your PC" prompt, uncheck "Always ask before opening this file" and click **Run**, or click **More info** followed by **Run anyway**.*
2. Wait for the terminal window to process the files.
3. Open the newly created `Save_For_Server_Mods` folder.
4. The `.jar` files inside `Save_For_Server_Mods` are... wait for it... safe for servers. Move these files to your server's `mods` folder.
5. You can leave `ClientModExtractor.java` and the `.bat`/`.sh` files in your `mods` folder— Minecraft won't touch them— but you should probably delete the `Save_For_Server_Mods` folder once you're done, since it's full of `.jar` files.

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
   - `displayTest = "IGNORE_ALL_VERSION"`

`displayTest = "IGNORE_SERVER_VERSION"` is not treated as client-only because Forge uses it for server-only compatibility and does not define physical loading behavior with that property.

### Dynamic Cloud Exclusions
The script reaches out via standard HTTP GET requests to fetch community-maintained blocklists (such as the `itzg` docker-minecraft-server JSON lists) and my own custom exclusion list. It extracts the JSON arrays using regex and builds a unified Hash Set of blocked mod IDs. Any mod that matches these IDs is skipped, even if its internal metadata claims it is server-safe. This list handles client-only projects whose packaged metadata incorrectly declares them as compatible with both sides.
