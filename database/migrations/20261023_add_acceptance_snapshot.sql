-- Select the intended database explicitly before applying this migration.
-- Existing records remain NULL/NULL and must not be backfilled with current data.
ALTER TABLE `project_acceptance`
  ADD COLUMN `snapshot_version` INT NULL,
  ADD COLUMN `snapshot_json` JSON NULL;
