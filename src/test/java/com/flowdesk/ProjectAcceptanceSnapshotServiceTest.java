package com.flowdesk;

import com.flowdesk.context.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.service.*;
import com.flowdesk.snapshot.AcceptanceSnapshotCodec;
import com.flowdesk.vo.ProjectAcceptanceDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ProjectAcceptanceSnapshotServiceTest {
    ProjectAcceptanceMapper acceptances=mock(ProjectAcceptanceMapper.class);
    TaskMapper tasks=mock(TaskMapper.class);
    ProjectMemberMapper members=mock(ProjectMemberMapper.class);
    AcceptanceSnapshotCodec codec=new AcceptanceSnapshotCodec(new ObjectMapper());
    ProjectAcceptanceService service=new ProjectAcceptanceService(acceptances,mock(ProjectMapper.class),tasks,
            mock(ProjectPermissionService.class),mock(OperationLogService.class),mock(TaskRequestMapper.class),
            mock(UserMapper.class),mock(NotificationService.class),members,mock(ProjectRequestClosureService.class),codec);
    @AfterEach void clear(){UserContext.remove();}

    @Test void snapshotReadNeverRebuildsFromLiveTasksOrMembers() {
        var detail=AcceptanceSnapshotCodecTest.delivery();
        String stored=codec.encode(detail);detail.setSnapshotJson(stored);detail.setSnapshotVersion(1);
        detail.setRepositoryUrl("https://example.com/live");detail.setProjectStatus("ARCHIVED");
        detail.setSubmittedAt(LocalDateTime.of(2026,10,23,12,0));
        when(acceptances.selectAcceptanceDetail(10L)).thenReturn(detail);
        when(acceptances.selectProjectAcceptances(4L)).thenReturn(List.of());
        UserContext.set(new CurrentUser(9L,"SYSTEM_ADMIN"));
        var result=service.getAcceptanceDetail(10L);
        assertEquals("https://example.com/A",result.getRepositoryUrl());assertEquals("ARCHIVED",result.getProjectStatus());
        assertEquals("SUBMISSION_SNAPSHOT",result.getDataSource());assertEquals(detail.getSubmittedAt(),result.getSnapshotCapturedAt());
        verifyNoInteractions(tasks,members);
    }
    @Test void damagedSnapshotNeverFallsBackToCurrentData() {
        var detail=new ProjectAcceptanceDetailVO();detail.setId(10L);detail.setProjectId(4L);
        detail.setSnapshotVersion(1);detail.setSnapshotJson("{}");
        when(acceptances.selectAcceptanceDetail(10L)).thenReturn(detail);
        UserContext.set(new CurrentUser(9L,"SYSTEM_ADMIN"));
        assertEquals(500,assertThrows(BusinessException.class,()->service.getAcceptanceDetail(10L)).getCode());
        verifyNoInteractions(tasks,members);
    }
    @Test void ordinaryUserStillCannotReadAdministratorDetails() {
        UserContext.set(new CurrentUser(1L,"USER"));
        assertEquals(403,assertThrows(BusinessException.class,()->service.getAcceptanceDetail(10L)).getCode());
        verifyNoInteractions(acceptances,tasks,members);
    }
}
