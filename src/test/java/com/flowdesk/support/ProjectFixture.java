package com.flowdesk.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 只创建和清理本用例拥有的项目及账号。 */
public final class ProjectFixture implements AutoCloseable {
    public final JdbcTemplate jdbc;
    public final List<Long> users = new ArrayList<>();
    public long projectId;
    public long manager;
    public long developer;
    public long target;

    private ProjectFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static ProjectFixture create(JdbcTemplate jdbc) {
        assertEquals("flowdesk_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("flowdesk_test_user@"));
        ProjectFixture fixture = new ProjectFixture(jdbc);
        try {
            fixture.manager = fixture.user();
            fixture.developer = fixture.user();
            fixture.target = fixture.user();
            fixture.projectId = fixture.insert("""
                INSERT INTO project (creator_id, name, description, goal, status)
                VALUES (?, ?, '测试描述', '测试目标', 'IN_PROGRESS')
                """, fixture.manager, "repair-" + UUID.randomUUID());
            fixture.member(fixture.manager, "PROJECT_MANAGER");
            fixture.member(fixture.developer, "DEVELOPER");
            fixture.member(fixture.target, "DEVELOPER");
            return fixture;
        } catch (RuntimeException | Error error) { fixture.close(); throw error; }
    }

    private long user() {
        long id = insert("""
            INSERT INTO user (username, password_hash, real_name, system_role, status)
            VALUES (?, ?, '回归测试用户', 'USER', 'ACTIVE')
            """, "repair_" + UUID.randomUUID(),
            org.springframework.security.crypto.bcrypt.BCrypt.hashpw("test-only", org.springframework.security.crypto.bcrypt.BCrypt.gensalt(4)));
        users.add(id);
        return id;
    }

    private void member(long userId, String role) {
        insert("INSERT INTO project_member (project_id, user_id, role, status) VALUES (?, ?, ?, 'ACTIVE')",
            projectId, userId, role);
    }

    public long task(long assignee, LocalDateTime deadline) {
        return insert("""
            INSERT INTO task (project_id, creator_id, assignee_id, title, description, goal, status, priority, deadline)
            VALUES (?, ?, ?, '回归任务', '任务描述', '任务目标', 'TODO', 'MEDIUM', ?)
            """, projectId, manager, assignee, deadline);
    }

    public long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        assertEquals(1, jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder));
        assertNotNull(holder.getKey());
        return holder.getKey().longValue();
    }

    public long count(String table) {
        if (!List.of("task", "task_request", "member_leave_request", "project_manager_transfer",
            "notification", "operation_log", "task_reminder_plan", "task_reminder_event", "project_member").contains(table))
            throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE project_id = ?", Long.class, projectId);
    }

    @Override
    public void close() {
        if (projectId != 0) {
            for (String table : List.of("task_submission", "task_comment"))
                jdbc.update("DELETE FROM " + table + " WHERE task_id IN (SELECT id FROM task WHERE project_id = ?)", projectId);
            for (String table : List.of("task_reminder_event", "task_reminder_plan", "notification", "operation_log",
                "task_request", "member_leave_request", "project_manager_transfer", "project_invitation",
                "project_acceptance", "task", "project_member"))
                jdbc.update("DELETE FROM " + table + " WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM project WHERE id = ?", projectId);
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id = ?", Long.class, projectId));
        }
        for (long user : users) {
            assertEquals(1, jdbc.update("DELETE FROM user WHERE id = ?", user));
        }
    }
}
