package com.flowdesk;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.NotificationMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Notification;
import com.flowdesk.model.User;
import com.flowdesk.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Notification.class);
    }

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUpRecipient() {
        lenient().when(userMapper.selectById(anyLong())).thenAnswer(invocation -> {
            User user = new User();
            user.setId(invocation.getArgument(0));
            user.setStatus("ACTIVE");
            user.setSystemRole("USER");
            return user;
        });
    }

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void newlyCreatedNotificationIsUnread() {

        CreateNotificationDTO dto = new CreateNotificationDTO();
        dto.setRecipientId(2L);
        dto.setActorId(1L);
        dto.setType("TASK_ASSIGNED");
        dto.setTitle("任务分配");
        dto.setContent("你被分配了任务：登录接口");
        dto.setProjectId(4L);
        dto.setTargetType("TASK");
        dto.setTargetId(9L);

        notificationService.createNotification(dto);

        ArgumentCaptor<Notification> captor =
                ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());

        Notification notification = captor.getValue();
        assertEquals(2L, notification.getRecipientId());
        assertEquals(1L, notification.getActorId());
        assertEquals("TASK_ASSIGNED", notification.getType());
        assertEquals("TASK", notification.getTargetType());
        assertEquals(9L, notification.getTargetId());
        assertNull(notification.getReadAt());
        assertNotNull(notification.getCreatedAt());
    }

    @Test
    void disabledRecipientDoesNotAccumulateNewNotifications() {
        User disabled = new User();
        disabled.setId(2L);
        disabled.setStatus("DISABLED");
        when(userMapper.selectById(2L)).thenReturn(disabled);

        CreateNotificationDTO dto = new CreateNotificationDTO();
        dto.setRecipientId(2L);
        dto.setType("TASK_ASSIGNED");
        dto.setTitle("任务分配");

        notificationService.createNotification(dto);

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void userCannotMarkAnotherUsersNotificationAsRead() {

        Notification notification = new Notification();
        notification.setId(20L);
        notification.setRecipientId(2L);

        UserContext.set(new CurrentUser(1L, "USER"));
        when(notificationMapper.selectById(20L)).thenReturn(notification);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> notificationService.markAsRead(20L)
        );

        assertEquals(403, exception.getCode());
        assertEquals("无权操作该通知", exception.getMessage());
        assertNull(notification.getReadAt());
        verify(notificationMapper, never()).updateById(notification);
    }

    @Test
    void markingNotificationAsReadIsPersistentAndIdempotent() {

        Notification notification = new Notification();
        notification.setId(20L);
        notification.setRecipientId(1L);

        UserContext.set(new CurrentUser(1L, "USER"));
        when(notificationMapper.selectById(20L)).thenReturn(notification);

        notificationService.markAsRead(20L);

        LocalDateTime firstReadAt = notification.getReadAt();
        assertNotNull(firstReadAt);
        verify(notificationMapper).updateById(notification);

        notificationService.markAsRead(20L);

        assertEquals(firstReadAt, notification.getReadAt());
        verify(notificationMapper, times(1)).updateById(notification);
    }

    @Test
    void notificationQueriesAndMarkAllAreScopedToCurrentUser() {

        UserContext.set(new CurrentUser(7L, "USER"));

        doAnswer(invocation -> {
            LambdaQueryWrapper<Notification> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            assertTrue(sql.contains("created_at") && sql.contains("id"));
            return java.util.List.of();
        }).when(notificationMapper).selectList(any());

        doAnswer(invocation -> {
            LambdaQueryWrapper<Notification> query = invocation.getArgument(0);
            query.getSqlSegment();
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            return 2L;
        }).when(notificationMapper).selectCount(any());

        doAnswer(invocation -> {
            LambdaUpdateWrapper<Notification> update = invocation.getArgument(1);
            update.getSqlSegment();
            assertTrue(update.getParamNameValuePairs().containsValue(7L));
            return 2;
        }).when(notificationMapper).update(isNull(), any());

        assertEquals(0, notificationService.getMyNotifications().size());
        assertEquals(2L, notificationService.getUnreadCount());
        notificationService.markAllAsRead();

        verify(notificationMapper).selectList(any());
        verify(notificationMapper).selectCount(any());
        verify(notificationMapper).update(isNull(), any());
    }
}
