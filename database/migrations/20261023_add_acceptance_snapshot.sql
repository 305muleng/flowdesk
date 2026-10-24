-- 执行迁移前，必须显式选择目标数据库。
-- 既有记录保留 NULL/NULL，不得使用当前数据回填。
ALTER TABLE `project_acceptance`
  ADD COLUMN `snapshot_version` INT NULL,
  ADD COLUMN `snapshot_json` JSON NULL;
