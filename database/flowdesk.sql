-- FlowDesk 完整数据库结构，共 15 张表。
-- 在已选择的空数据库中执行，不包含账号或业务数据。
SET NAMES utf8mb4;

CREATE TABLE `member_leave_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `applicant_id` bigint NOT NULL,
  `reason` text NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `closed_at` datetime DEFAULT NULL,
  `close_reason` varchar(32) DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `review_note` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` datetime NOT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_leave_project_status` (`project_id`,`status`),
  KEY `idx_leave_applicant_status` (`applicant_id`,`status`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recipient_id` bigint NOT NULL,
  `actor_id` bigint DEFAULT NULL,
  `type` varchar(50) NOT NULL,
  `title` varchar(100) NOT NULL,
  `content` varchar(500) NOT NULL,
  `project_id` bigint DEFAULT NULL,
  `target_type` varchar(50) NOT NULL,
  `target_id` bigint NOT NULL,
  `read_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notification_recipient_created` (`recipient_id`,`created_at`),
  KEY `idx_notification_recipient_read` (`recipient_id`,`read_at`,`created_at`),
  KEY `idx_notification_target` (`target_type`,`target_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `actor_id` bigint NOT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint NOT NULL,
  `action` varchar(64) NOT NULL,
  `description` varchar(500) NOT NULL,
  `before_data` json DEFAULT NULL,
  `after_data` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_log_project_time` (`project_id`,`created_at`),
  KEY `idx_log_target_time` (`target_type`,`target_id`,`created_at`),
  KEY `idx_log_actor` (`actor_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `creator_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` text,
  `goal` text,
  `status` varchar(32) NOT NULL DEFAULT 'PREPARING',
  `start_time` datetime DEFAULT NULL,
  `expected_end_time` datetime DEFAULT NULL,
  `actual_end_time` datetime DEFAULT NULL,
  `cancel_reason` text,
  `cancelled_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  `repository_url` varchar(500) DEFAULT NULL,
  `deploy_url` varchar(500) DEFAULT NULL,
  `document_url` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `project_acceptance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `submitter_id` bigint NOT NULL,
  `acceptance_no` int NOT NULL,
  `submission_note` text NOT NULL,
  `review_status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `reviewer_id` bigint DEFAULT NULL,
  `review_note` text,
  `submitted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` datetime DEFAULT NULL,
  `snapshot_version` int DEFAULT NULL,
  `snapshot_json` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_acceptance_project_no` (`project_id`,`acceptance_no`),
  KEY `idx_acceptance_project_time` (`project_id`,`submitted_at`),
  KEY `idx_acceptance_reviewer_status` (`reviewer_id`,`review_status`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `project_invitation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `inviter_id` bigint NOT NULL,
  `invitee_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `closed_at` datetime DEFAULT NULL,
  `close_reason` varchar(32) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` datetime NOT NULL,
  `responded_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_invitation_project_invitee` (`project_id`,`invitee_id`),
  KEY `idx_invitation_invitee_status` (`invitee_id`,`status`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `project_manager_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `from_user_id` bigint NOT NULL,
  `to_user_id` bigint NOT NULL,
  `old_manager_action` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `closed_at` datetime DEFAULT NULL,
  `close_reason` varchar(32) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` datetime NOT NULL,
  `responded_at` datetime DEFAULT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_transfer_project_status` (`project_id`,`status`),
  KEY `idx_transfer_to_user_status` (`to_user_id`,`status`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `project_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `role` varchar(32) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `joined_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `left_at` datetime DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_user` (`project_id`,`user_id`),
  KEY `idx_project_member_user` (`user_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `creator_id` bigint NOT NULL,
  `assignee_id` bigint DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `description` text NOT NULL,
  `goal` text NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'TODO',
  `priority` varchar(20) NOT NULL DEFAULT 'MEDIUM',
  `deadline` datetime NOT NULL,
  `cancel_reason` text,
  `completed_at` datetime DEFAULT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_assignee` (`assignee_id`),
  KEY `idx_task_project_status` (`project_id`,`status`),
  KEY `idx_task_project_deadline` (`project_id`,`deadline`),
  KEY `idx_task_project_deleted_status` (`project_id`,`deleted_at`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `author_id` bigint NOT NULL,
  `parent_id` bigint DEFAULT NULL,
  `content` text NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `edited_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_comment_task_time` (`task_id`,`created_at`),
  KEY `idx_comment_parent` (`parent_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `requester_id` bigint NOT NULL,
  `title` varchar(200) NOT NULL,
  `description` text NOT NULL,
  `goal` text NOT NULL,
  `suggested_deadline` datetime DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `closed_at` datetime DEFAULT NULL,
  `close_reason` varchar(32) DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `review_note` text,
  `task_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` datetime DEFAULT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_request_project_status` (`project_id`,`status`),
  KEY `idx_task_request_requester` (`requester_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `submitter_id` bigint NOT NULL,
  `submission_no` int NOT NULL,
  `completion_note` text NOT NULL,
  `result_url` varchar(500) DEFAULT NULL,
  `test_note` text NOT NULL,
  `review_status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `reviewer_id` bigint DEFAULT NULL,
  `review_note` text,
  `submitted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deadline_at_submission` datetime DEFAULT NULL COMMENT '本次提交时生效的截止时间',
  `reviewed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_submission_task_no` (`task_id`,`submission_no`),
  KEY `idx_submission_task_time` (`task_id`,`submitted_at`),
  KEY `idx_submission_reviewer_status` (`reviewer_id`,`review_status`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `real_name` varchar(100) NOT NULL,
  `system_role` varchar(32) NOT NULL DEFAULT 'USER',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_reminder_plan (
  task_id BIGINT NOT NULL PRIMARY KEY,
  project_id BIGINT NOT NULL,
  deadline DATETIME NOT NULL,
  assignee_id BIGINT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  KEY idx_reminder_deadline (deadline, task_id),
  KEY idx_reminder_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_reminder_event (
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
