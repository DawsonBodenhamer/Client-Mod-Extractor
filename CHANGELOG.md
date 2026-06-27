# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.2] - 2026-06-27

### Changed
- Added `crash_assistant`, `mod-loading-screen`, and `satisfying_buttons` to `custom-excludes.txt`

---

## [1.0.1] - 2026-06-26

### Changed
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
