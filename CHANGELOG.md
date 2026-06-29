# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] - 2026-06-29

### Added
- **Better Reliability**
  - Added pre-flight system checks to `.bat` and `.sh` launchers to verify correct Java JDK installation before execution.
  - Added clear error messaging with direct download links/commands for missing JDK installations.
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
