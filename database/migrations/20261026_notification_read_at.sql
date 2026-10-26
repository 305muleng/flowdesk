-- read_at 为已读状态的唯一依据；执行前核实目标数据库。
ALTER TABLE notification
  DROP INDEX idx_notification_recipient_read,
  DROP COLUMN is_read,
  ADD INDEX idx_notification_recipient_read (recipient_id, read_at, created_at);
