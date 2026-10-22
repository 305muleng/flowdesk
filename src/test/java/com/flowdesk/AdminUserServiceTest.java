package com.flowdesk;

import com.flowdesk.enums.UserStatus;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.service.AdminUserService;
import com.flowdesk.event.UserDisabledEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AdminUserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private com.flowdesk.mapper.ProjectMemberMapper projectMemberMapper;

    @Mock
    private ApplicationEventPublisher events;

    @org.junit.jupiter.api.BeforeEach
    void setUpMemberships() {
        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));
    }

    @InjectMocks
    private AdminUserService adminUserService;

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    /**
     * 管理员禁用普通用户
     * ACTIVE -> DISABLED
     */
    @Test
    void disableUserChangesStatusToDisabled() {
        User user = new User();
        user.setId(2L);
        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        LocalDateTime before = LocalDateTime.now();

        // 当前登录的是管理员 1
        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));

        when(userMapper.selectByIdForUpdate(2L)).thenReturn(user);

        adminUserService.disableUser(2L);

        assertEquals(UserStatus.DISABLED, user.getStatus());
        assertTrue(!user.getUpdatedAt().isBefore(before) && !user.getUpdatedAt().isAfter(LocalDateTime.now()));

        verify(userMapper).updateById(user);
        verify(events).publishEvent(any(UserDisabledEvent.class));
    }

    /**
     * 管理员恢复被禁用用户
     * DISABLED -> ACTIVE
     */
    @Test
    void enableUserChangesStatusToActive() {
        User user = new User();
        user.setId(2L);
        user.setStatus(UserStatus.DISABLED);
        user.setUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        LocalDateTime before = LocalDateTime.now();

        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));

        when(userMapper.selectById(2L)).thenReturn(user);

        adminUserService.enableUser(2L);

        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertTrue(!user.getUpdatedAt().isBefore(before) && !user.getUpdatedAt().isAfter(LocalDateTime.now()));

        verify(userMapper).updateById(user);
    }

    /**
     * 管理员不能禁用自己
     */
    @Test
    void adminCannotDisableSelf() {
        User admin = new User();
        admin.setId(1L);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setSystemRole("SYSTEM_ADMIN");

        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));

        when(userMapper.selectByIdForUpdate(1L)).thenReturn(admin);

        BusinessException exception = assertThrows(BusinessException.class, () -> adminUserService.disableUser(1L));

        assertEquals("不能禁用自己的账号", exception.getMessage());
    }

    @Test
    void repeatedDisableIsIdempotent() {
        User user = new User();
        user.setId(2L);
        user.setStatus(UserStatus.DISABLED);
        LocalDateTime previousUpdate = LocalDateTime.of(2020, 1, 1, 0, 0);
        user.setUpdatedAt(previousUpdate);
        UserContext.set(new CurrentUser(1L, "SYSTEM_ADMIN"));
        when(userMapper.selectByIdForUpdate(2L)).thenReturn(user);

        adminUserService.disableUser(2L);

        verify(userMapper, never()).updateById(any(User.class));
        assertEquals(previousUpdate, user.getUpdatedAt());
        org.mockito.Mockito.verifyNoInteractions(events);
    }

    @Test
    void repeatedEnableIsIdempotent() {
        User user = new User();
        user.setId(2L);
        user.setStatus(UserStatus.ACTIVE);
        LocalDateTime previousUpdate = LocalDateTime.of(2020, 1, 1, 0, 0);
        user.setUpdatedAt(previousUpdate);
        when(userMapper.selectById(2L)).thenReturn(user);

        adminUserService.enableUser(2L);

        verify(userMapper, never()).updateById(any(User.class));
        assertEquals(previousUpdate, user.getUpdatedAt());
    }

    @Test
    void missingUserCannotBeDisabledOrEnabled() {
        when(userMapper.selectByIdForUpdate(99L)).thenReturn(null);
        when(userMapper.selectById(99L)).thenReturn(null);

        BusinessException disable = assertThrows(BusinessException.class, () -> adminUserService.disableUser(99L));
        BusinessException enable = assertThrows(BusinessException.class, () -> adminUserService.enableUser(99L));

        assertEquals(404, disable.getCode());
        assertEquals(404, enable.getCode());
    }

    @Test
    void invalidPaginationAndFiltersAreRejected() {
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> adminUserService.getUsers(0, 10, null, null, null)).getCode()
        );
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> adminUserService.getUsers(1, 0, null, null, null)).getCode()
        );
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> adminUserService.getUsers(1, 101, null, null, null)).getCode()
        );
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> adminUserService.getUsers(1, 10, null, "ABC", null)).getCode()
        );
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> adminUserService.getUsers(1, 10, null, null, "ROOT")).getCode()
        );
    }
}
