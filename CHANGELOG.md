# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.11] - Unreleased

---

## [1.0.10] - 2026-08-28

### Fixed
- **Server-Safe Mod Detection**
  - Fixed cases where examples left inside Forge and NeoForge mod files could make the extractor remove mods that are safe for servers.
  - The extractor no longer mistakes a compatibility setting (`IGNORE_ALL_VERSION`) for proof that a mod is client-only.
  - Iceberg is now kept for servers. Particular and Pretty Rain are also kept when using Forge 1.20.1, while their client-only NeoForge versions are still removed.

---

## [1.0.9] - 2026-08-26

### Added
- **Blacklisted Mods**
  - Added `perception` because its Forge and NeoForge metadata does not identify the mod as client-only.

---

## [1.0.8] - 2026-08-20

### Added
- **Blacklisted Mods**
  - Added `coolrain`, `eg_particle_interactions`, `norealmsbutton`, and `windy` because they do not expose the correct client-only metadata.

### Fixed
- **Exclusion Precedence**
  - Force-included compatible mods now override community exclusions without overriding explicit loader metadata. FOr example, AppleSkin is now force-included so its compatible Fabric and NeoForge builds are copied.
- **Cross-Loader Side Classification**
  - Stopped interpreting `side = "CLIENT"` inside a dependency declaration as proof that the mod itself is client-only. That field only describes where the dependency is used; direct Fabric, Forge, and NeoForge client-only metadata remains respected.

---

## [1.0.7] - 2026-08-14

### Added
- **Java Installation Guidance**
  - Added color-coded, beginner-friendly Java installation steps for Windows, Linux, and macOS when Java is missing, outdated, or cannot be identified.

### Fixed
- **Java Launcher Compatibility**
  - Added an explicit Java 11 minimum-version check, preventing Java 8 from reporting the misleading `ClientModExtractor.java` main-class error.
  - Made the Windows launcher run from its own directory so it can reliably locate `ClientModExtractor.java`.
  - Preserved the extractor's exit status so launcher failures are reported correctly.

---

## [1.0.6] - 2026-07-30

### Added
- **Blacklisted Mods**
  - Added `moremousetweaks` and `ssrcamerafixes` because their packaged metadata does not identify the mods themselves as client-only.

### Fixed
- **Loader-Specific Client Mod Detection**
  - Added a NeoForge-only exclusion for `advancementplaques`, preventing its missing Iceberg dependency from crashing extracted servers.
  - Preserved Advancement Plaques on Forge, where the mod may be required on both the client and server.

---

## [1.0.5] - 2026-07-20

### Added
- **Blacklisted Mods**
  - Added `armor_3d` and `betterclouds` because their packaged metadata does not identify them as client-only.

### Fixed
- **Client Mod Detection**
  - Stopped treating `displayTest = "IGNORE_SERVER_VERSION"` as client-only metadata, allowing server-side mods such as `leavesbegone` to be copied correctly.

---

## [1.0.4] - 2026-07-04

### Added
- **Blacklisted Mods**
  - Added `cwb` (Cubes Without Borders updated Mod ID).
  - Added `enhanced-attack-indicator`.
  - Added `sodiumextras` and `sodium-extras` to account for NeoForge port naming variants.
  - Added `equipment-compare` naming variant.

### Fixed
- **Custom Exclusions Parsing**
  - Fixed an issue where the `custom-excludes.txt` list was being parsed strictly by newlines instead of commas, which prevented uniquely listed mods (like `mod-loading-screen`, `satisfying_buttons`, `equipmentcompare`, and `crash_assistant`) from being correctly flagged.

---

## [1.0.3] - 2026-06-29

### Added
- **Better Reliability**
  - Added pre-flight system checks to `.bat` and `.sh` launchers to verify correct Java JDK installation before execution.
  - Added pre-flight update checker with beginner-friendly installation reminder when updates are available on GitHub.
  - Added clear error messaging with direct download links/commands for missing JDK installations.
  - Added 24-bit RGB ANSI color coding to standard terminal output for improved readability.
  - Integrated dynamic console coloration targeting path directories and search logs.
  - Appended an active community reporting directive guiding users to submit unmapped server crashes directly to GitHub.
- **Blacklisted Mods**
  - Added `equipmentcompare` to `custom-excludes.txt`

### Fixed
- **Client Mod Detection**
  - Fixed an issue where remote blacklists failed to match mods due to hyphens vs. underscores in Mod IDs.
  - Updated NeoForge metadata parsing to identify mods that declare client-side restrictions within base-game dependencies.
- **Package Declarations**
  - Updated `publish_release.py` to dynamically strip package path declarations during the build process to maintain JEP 330 execution compatibility.
- **Output Directory**
  - Renamed output directory from `ServerMods` to `Save_For_Server_Mods` to eliminate ambiguity for beginners.

---

## [1.0.2] - 2026-06-27

### Changed

- **Blacklisted Mods**
  - Added `crash_assistant`, `mod-loading-screen`, and `satisfying_buttons` to `custom-excludes.txt`

---

## [1.0.1] - 2026-06-26

### Changed
- **Blacklisted Mods**
  - Removed Midnight Lib from `custom-excludes.txt`

---

## [1.0.0] - 2026-06-25

### Added
- **Centralized Java Logic**
  - Consolidated extraction logic into `ModExtractor.java` for cross-platform compatibility.
  - Implemented remote fetching for both public and custom exclusion lists.
  - Added lightweight launcher scripts (`Run-Extractor-Windows.bat` and `Run-Extractor-Linux.sh`).
  - Aligned CLI output columns using fixed-width string formatting for improved readability.
- **Dynamic Exclusion System**
  - Fetches known problematic server mods from GitHub dynamically.
  - Gracefully falls back to local `custom-excludes.txt` if offline.

### Fixed
- **Mod Parsing Architecture**
  - Rewrote TOML parsing to explicitly strip out `[[dependencies` and `[[mixins` blocks to prevent false-positive client-only flagging (fixes the issue where mods like `Create` were incorrectly flagged).

---
