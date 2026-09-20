package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.service.ProjectPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectPermissionServiceTest {

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @InjectMocks
    private ProjectPermissionService projectPermissionService;

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void systemAdminCannotUseHistoricalProjectMembership() {
        UserContext.set(new CurrentUser(9L, "SYSTEM_ADMIN"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectPermissionService.requireProjectMember(4L)
        );

        assertEquals(403, exception.getCode());
        assertEquals("系统管理员不能参与普通项目", exception.getMessage());
        verify(projectMemberMapper, never()).selectOne(any());
    }

    @Test
    void inactiveOrMissingMembershipIsRejected() {
        UserContext.set(new CurrentUser(2L, "USER"));
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectPermissionService.requireProjectMember(4L)
        );

        assertEquals(403, exception.getCode());
    }

    @Test
    void developerCannotUseManagerPermission() {
        UserContext.set(new CurrentUser(2L, "USER"));
        ProjectMember member = new ProjectMember();
        member.setRole("DEVELOPER");
        when(projectMemberMapper.selectOne(any())).thenReturn(member);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> projectPermissionService.requireProjectManager(4L)
        );

        assertEquals(403, exception.getCode());
    }
}
