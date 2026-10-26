package com.flowdesk;

import com.flowdesk.context.*;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.service.NotificationService;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class NotificationReadIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notifications;

    @Test
    void readAtAloneDrivesReadFiltersAndUnreadCount() {
        try (var f = ProjectFixture.create(jdbc)) {
            var dto = new CreateNotificationDTO();
            dto.setRecipientId(f.developer); dto.setActorId(f.manager);
            dto.setType("TASK_ASSIGNED"); dto.setTitle("测试通知"); dto.setContent("通知内容");
            dto.setProjectId(f.projectId); dto.setTargetType("PROJECT"); dto.setTargetId(f.projectId);
            notifications.createNotification(dto);
            notifications.createNotification(dto);
            UserContext.set(new CurrentUser(f.developer, "USER"));
            assertEquals(2L, notifications.getUnreadCount());
            var unread = notifications.getMyNotifications(1, 20, "UNREAD", "ALL");
            assertEquals(2L, unread.getTotal());
            notifications.markAsRead(unread.getRecords().get(0).getId());
            assertEquals(1L, notifications.getUnreadCount());
            assertEquals(1L, notifications.getMyNotifications(1, 20, "READ", "ALL").getTotal());
            notifications.markAllAsRead();
            assertEquals(0L, notifications.getUnreadCount());
            assertEquals(2L, notifications.getMyNotifications(1, 20, "READ", "ALL").getTotal());
            assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'notification' AND column_name = 'is_read'
                """, Long.class));
            assertEquals("recipient_id,read_at,created_at", jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'notification' AND index_name = 'idx_notification_recipient_read'
                """, String.class));
        } finally { UserContext.remove(); }
    }
}
