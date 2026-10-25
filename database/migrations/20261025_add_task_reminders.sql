-- 到期提醒：仅建表，不修改既有业务数据；由维护者在确认目标库后执行。
CREATE TABLE IF NOT EXISTS task_reminder_plan (
  task_id BIGINT NOT NULL PRIMARY KEY,
  project_id BIGINT NOT NULL,
  deadline DATETIME NOT NULL,
  assignee_id BIGINT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  KEY idx_reminder_deadline (deadline, task_id),
  KEY idx_reminder_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_reminder_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  plan_revision BIGINT NOT NULL,
  kind VARCHAR(20) NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  next_publish_at DATETIME NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(200) NULL,
  created_at DATETIME NOT NULL,
  finished_at DATETIME NULL,
  UNIQUE KEY uk_reminder_event (task_id, plan_revision, kind),
  KEY idx_reminder_publish (state, next_publish_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
