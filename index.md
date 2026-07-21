# Client Mod Extractor

> Independent development repository migrated from `C:\mods\Data\Repositories\ClientModExtractor`.

## Development Context

- Repository root: `C:\Vault\dev\client_mod_extractor`
- Branch baseline: `main` at `881b20147b8b6aa8bc3a6fca876daf8944dc6d34`
- Migration record: [[client_mod_extractor_vault_migration]]
- Migration plan: [[client_mod_extractor_vault_migration_plan]]
- Vault development index: [[dev/index]]

## Operational Notes

- Tracked files under `tools/` are protected by git-crypt and require the preserved local key/filter state.
- Open `C:\Vault` as the IntelliJ project. Shared `CME - ...` run configurations live in the vault-root `.run/` directory.
- The GitHub release runner publishes externally and modifies Git state. Do not execute it for configuration validation.

## See Also

- [[dev/index]]
- [[client_mod_extractor_vault_migration]]
- [[client_mod_extractor_vault_migration_source_manifest]]
