package com.flowdesk;

import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import com.flowdesk.vo.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemberLifecycleRegressionTest {
    @Mock TaskMapper tasks;
    @Mock ProjectMapper projects;
    @Mock ProjectMemberMapper members;
    @Mock TaskSubmissionMapper submissions;
    @Mock TaskCommentMapper comments;
    @Mock UserMapper users;
    @Mock TaskRequestMapper requests;
    @Mock MemberLeaveRequestMapper leaves;
    @Mock ProjectManagerTransferMapper transfers;
    @Mock ProjectPermissionService permissions;
    @Mock NotificationService notifications;
    @Mock OperationLogService logs;
    @Mock PasswordEncoder passwords;
    TaskService taskService;
    TaskRequestService requestService;
    MemberLeaveRequestService leaveService;
    ProjectManagerTransferService transferService;

    @BeforeEach void setup() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), ProjectMember.class);
        UserContext.set(new CurrentUser(1L, "USER"));
        taskService = new TaskService(tasks, projects, members, permissions, submissions, comments, logs, notifications, users);
        requestService = new TaskRequestService(requests, projects, permissions, tasks, members, notifications, users, logs);
        transferService = new ProjectManagerTransferService(transfers, projects, users, tasks, permissions, notifications, logs, passwords, members, requests, leaves, 7);
        leaveService = new MemberLeaveRequestService(leaves, projects, members, permissions, tasks, notifications, logs, requests, 7);
    }
    @AfterEach void cleanup() { UserContext.remove(); }
    Project project() { Project p = new Project(); p.setId(4L); p.setStatus("IN_PROGRESS"); p.setName("FlowDesk"); return p; }
    User user() { User u = new User(); u.setId(2L); u.setStatus("ACTIVE"); u.setSystemRole("USER"); return u; }
    ProjectMember developer() { ProjectMember m = new ProjectMember(); m.setId(8L); m.setUserId(2L); m.setProjectId(4L); m.setRole("DEVELOPER"); m.setStatus("ACTIVE"); return m; }
    ProjectMember manager() { ProjectMember m = developer(); m.setUserId(1L); m.setRole("PROJECT_MANAGER"); return m; }
    Task task(String status) { Task t = new Task(); t.setId(10L); t.setProjectId(4L); t.setStatus(status); t.setTitle("Task"); return t; }

    @Test void assignmentLocksProjectBeforeReadingCandidateAndWritingTask() {
        Task task = task("TODO"); AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task);
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(manager());
        when(members.selectMemberForUpdate(4L, 2L)).thenReturn(developer());
        when(users.selectByIdForUpdate(2L)).thenReturn(user());
        when(tasks.reassignTodoIfCurrent(eq(10L), isNull(), eq(2L), any())).thenReturn(1);
        taskService.assignTask(10L, dto);
        InOrder order = inOrder(projects, members, users, tasks);
        order.verify(tasks).selectById(10L);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 1L);
        order.verify(members).selectMemberForUpdate(4L, 2L);
        order.verify(users).selectByIdForUpdate(2L);
        order.verify(tasks).reassignTodoIfCurrent(eq(10L), isNull(), eq(2L), any());
        verify(projects, never()).selectById(anyLong());
        verify(members, never()).selectOne(any());
        verify(users, never()).selectById(anyLong());
        verify(permissions, times(1)).requireProjectManager(4L);
    }

    @Test void departureWinningProjectLockPreventsAssignment() {
        AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task("TODO"));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        // The current membership read after acquiring the lock sees the committed departure.
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(manager());
        when(members.selectMemberForUpdate(4L, 2L)).thenReturn(null);
        assertEquals(409, assertThrows(BusinessException.class, () -> taskService.assignTask(10L, dto)).getCode());
        verify(tasks, never()).reassignTodoIfCurrent(anyLong(), any(), anyLong(), any());
    }

    @Test void disabledMemberCannotBeAssigned() {
        AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task("TODO"));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(manager());
        when(members.selectMemberForUpdate(4L, 2L)).thenReturn(developer());
        User u = user(); u.setStatus("DISABLED"); when(users.selectByIdForUpdate(2L)).thenReturn(u);
        assertEquals(409, assertThrows(BusinessException.class, () -> taskService.assignTask(10L, dto)).getCode());
        InOrder order = inOrder(projects, members, users);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 1L);
        order.verify(members).selectMemberForUpdate(4L, 2L);
        order.verify(users).selectByIdForUpdate(2L);
        verify(tasks, never()).reassignTodoIfCurrent(anyLong(), any(), anyLong(), any());
    }

    @Test void operatorDemotedWhileWaitingForProjectLockCannotAssign() {
        AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task("TODO"));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        ProjectMember demoted = manager(); demoted.setRole("DEVELOPER");
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(demoted);
        assertEquals(403, assertThrows(BusinessException.class, () -> taskService.assignTask(10L, dto)).getCode());
        InOrder order = inOrder(permissions, projects, members);
        order.verify(permissions).requireProjectManager(4L);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 1L);
        verify(members, never()).selectMemberForUpdate(4L, 2L);
        verify(users, never()).selectByIdForUpdate(anyLong());
        verify(tasks, never()).reassignTodoIfCurrent(anyLong(), any(), anyLong(), any());
    }

    @Test void inactiveAssigneeAfterProjectLockCannotReceiveTask() {
        AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task("TODO"));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(manager());
        ProjectMember inactive = developer(); inactive.setStatus("INACTIVE");
        when(members.selectMemberForUpdate(4L, 2L)).thenReturn(inactive);
        assertEquals(409, assertThrows(BusinessException.class, () -> taskService.assignTask(10L, dto)).getCode());
        InOrder order = inOrder(projects, members);
        order.verify(projects).selectByIdForUpdate(4L);
        order.verify(members).selectMemberForUpdate(4L, 1L);
        order.verify(members).selectMemberForUpdate(4L, 2L);
        verify(users, never()).selectByIdForUpdate(anyLong());
        verify(tasks, never()).reassignTodoIfCurrent(anyLong(), any(), anyLong(), any());
    }

    @Test void administratorAccountCannotBecomeAssignee() {
        AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task("TODO"));
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(manager());
        when(members.selectMemberForUpdate(4L, 2L)).thenReturn(developer());
        User admin = user(); admin.setSystemRole("SYSTEM_ADMIN");
        when(users.selectByIdForUpdate(2L)).thenReturn(admin);
        assertEquals(409, assertThrows(BusinessException.class, () -> taskService.assignTask(10L, dto)).getCode());
        verify(tasks, never()).reassignTodoIfCurrent(anyLong(), any(), anyLong(), any());
    }

    @Test void unchangedAssigneeReturnsWithoutWritingTask() {
        Task task = task("TODO"); task.setAssigneeId(2L);
        AssignTaskDTO dto = new AssignTaskDTO(); dto.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task);
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        when(members.selectMemberForUpdate(4L, 1L)).thenReturn(manager());
        when(members.selectMemberForUpdate(4L, 2L)).thenReturn(developer());
        when(users.selectByIdForUpdate(2L)).thenReturn(user());
        taskService.assignTask(10L, dto);
        verify(tasks, never()).reassignTodoIfCurrent(anyLong(), any(), anyLong(), any());
        verifyNoInteractions(notifications, logs, leaves);
    }

    void pendingRequest(Long requester) {
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        TaskRequest r = new TaskRequest(); r.setId(12L); r.setProjectId(4L); r.setRequesterId(requester); r.setStatus("PENDING");
        when(requests.selectById(12L)).thenReturn(r);
    }
    @Test void managerCannotReviewOwnFormerDeveloperRequest() {
        pendingRequest(1L); ReviewTaskRequestDTO dto = new ReviewTaskRequestDTO(); dto.setAction("APPROVE");
        assertEquals(403, assertThrows(BusinessException.class, () -> requestService.reviewTaskRequest(4L, 12L, dto)).getCode());
        verify(requests, never()).reviewIfPending(anyLong(), anyLong(), anyString(), anyLong(), any(), any());
        verify(tasks, never()).insert(any(Task.class));
    }
    @Test void requesterPromotedToManagerCannotHaveDeveloperRequestApproved() {
        pendingRequest(2L); ProjectMember m = developer(); m.setRole("PROJECT_MANAGER");
        when(members.selectOne(any())).thenReturn(m);
        ReviewTaskRequestDTO dto = new ReviewTaskRequestDTO(); dto.setAction("APPROVE");
        assertEquals(409, assertThrows(BusinessException.class, () -> requestService.reviewTaskRequest(4L, 12L, dto)).getCode());
        verify(tasks, never()).insert(any(Task.class));
    }
    @Test void nonDeveloperCannotHaveRequestRejectedEither() {
        pendingRequest(2L); when(members.selectOne(any())).thenReturn(null);
        ReviewTaskRequestDTO dto = new ReviewTaskRequestDTO(); dto.setAction("REJECT");
        assertEquals(409, assertThrows(BusinessException.class, () -> requestService.reviewTaskRequest(4L, 12L, dto)).getCode());
        verify(requests, never()).reviewIfPending(anyLong(), anyLong(), anyString(), anyLong(), any(), any());
    }
    @Test void transferAcceptanceCancelsNewManagersPendingRequests() {
        UserContext.set(new CurrentUser(2L, "USER"));
        ProjectManagerTransfer t = new ProjectManagerTransfer(); t.setId(20L); t.setProjectId(4L); t.setFromUserId(1L); t.setToUserId(2L); t.setStatus("PENDING"); t.setOldManagerAction("STAY"); t.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(transfers.selectById(20L)).thenReturn(t);
        when(projects.selectByIdForUpdate(4L)).thenReturn(project());
        when(users.selectByIdForUpdate(2L)).thenReturn(user());
        when(members.selectOne(any())).thenReturn(developer());
        when(transfers.acceptIfPending(eq(20L), eq(4L), eq(2L), any(), any())).thenReturn(1);
        when(members.promoteDeveloperToManager(eq(4L), eq(2L), any())).thenReturn(1);
        when(members.demoteManagerToDeveloper(eq(4L), eq(1L), any())).thenReturn(1);
        transferService.acceptTransfer(4L, 20L);
        verify(requests).cancelPendingByRequester(eq(4L), eq(2L), any());
        verify(leaves).cancelPendingByApplicant(eq(4L), eq(2L), any());
        verify(requests, never()).cancelPendingByRequester(eq(4L), eq(1L), any());
    }
    @Test void disabledAssigneeReviewRejectionReturnsTodoAndClearsAssignee() {
        Task task = task("REVIEW"); task.setAssigneeId(2L);
        when(tasks.selectById(10L)).thenReturn(task);
        when(projects.selectById(4L)).thenReturn(project());
        TaskSubmission submission = new TaskSubmission(); submission.setId(30L); submission.setSubmitterId(2L); submission.setReviewStatus("PENDING");
        when(submissions.selectPendingSubmission(10L)).thenReturn(submission);
        User u = user(); u.setStatus("DISABLED"); when(users.selectById(2L)).thenReturn(u);
        when(tasks.rejectToTodoAndUnassign(eq(10L), eq(2L), any())).thenReturn(1);
        when(submissions.reviewPendingSubmission(eq(30L), eq(10L), eq("REJECTED"), eq(1L), any(), any())).thenReturn(1);
        ReviewTaskDTO dto = new ReviewTaskDTO(); dto.setAction("REJECT"); dto.setReviewNote("需要修改");
        taskService.reviewTask(10L, dto);
        assertEquals("TODO", task.getStatus()); assertNull(task.getAssigneeId());
        verify(tasks).rejectToTodoAndUnassign(eq(10L), eq(2L), any());
    }
    @Test void projectCandidateQueryRequiresCurrentManager() {
        ProjectService service = new ProjectService(projects, members, permissions, logs, tasks, requests, leaves, notifications, users);
        when(users.selectMemberCandidates(eq(4L), any())).thenReturn(List.of());
        service.getMemberCandidates(4L);
        verify(permissions).requireProjectManager(4L);
        doThrow(new BusinessException(403, "无权查看")).when(permissions).requireProjectManager(4L);
        assertThrows(BusinessException.class, () -> service.getMemberCandidates(4L));
    }
    @Test void newManagerCanDiscoverExistingPendingLeaveRequests() {
        UserContext.set(new CurrentUser(3L, "USER"));
        MemberLeaveRequestVO vo = new MemberLeaveRequestVO(); vo.setId(40L); vo.setApplicantId(2L); vo.setReason("个人原因"); vo.setStatus("PENDING");
        when(leaves.selectPendingForProject(eq(4L), any())).thenReturn(List.of(vo));
        assertEquals(40L, leaveService.getPendingLeaveRequests(4L).get(0).getId());
        verify(permissions).requireProjectManager(4L);
        doThrow(new BusinessException(403, "不是负责人")).when(permissions).requireProjectManager(4L);
        assertThrows(BusinessException.class, () -> leaveService.getPendingLeaveRequests(4L));
        verify(leaves, times(1)).selectPendingForProject(eq(4L), any());
    }
    @Test void disabledMembershipIsPreservedInManagementResponse() {
        ProjectService service = new ProjectService(projects, members, permissions, logs, tasks, requests, leaves, notifications, users);
        ProjectMemberVO vo = new ProjectMemberVO(); vo.setUserId(2L); vo.setUserStatus("DISABLED"); vo.setEffective(false); vo.setUnfinishedTaskCount(2);
        when(members.selectActiveMembers(4L)).thenReturn(List.of(vo));
        List<ProjectMemberVO> result = service.getProjectMembers(4L);
        assertEquals(1, result.size()); assertFalse(result.get(0).isEffective());
        assertEquals("DISABLED", result.get(0).getUserStatus()); assertEquals(2, result.get(0).getUnfinishedTaskCount());
    }

    // Guard actual mapper predicates without introducing a replacement database or schema.
    String selectSql(Class<?> type, String method) {
        return java.util.Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow().getAnnotation(Select.class).value()[0];
    }
    String updateSql(Class<?> type, String method) {
        return java.util.Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow().getAnnotation(Update.class).value()[0];
    }
    @Test void lazyExpiredLeavesExcludedFromBulkCancellation() {
        String sql = updateSql(MemberLeaveRequestMapper.class, "cancelPendingByApplicant");
        assertTrue(sql.contains("status = 'PENDING'")); assertTrue(sql.contains("expires_at > #{cancelledAt}"));
    }
    @Test void resultUrlRejectsNonHttpSchemesBeforeChangingTask() {
        Task task = task("IN_PROGRESS"); task.setAssigneeId(1L);
        when(tasks.selectById(10L)).thenReturn(task);
        when(projects.selectById(4L)).thenReturn(project());
        SubmitTaskDTO dto = new SubmitTaskDTO(); dto.setCompletionNote("完成"); dto.setTestNote("测试通过");
        for (String value : List.of("javascript:alert(1)", "data:text/html,test", "file:///tmp/test", "ftp://example.com", "https://", "/relative")) {
            dto.setResultUrl(value);
            assertEquals(400, assertThrows(BusinessException.class, () -> taskService.submitTask(10L, dto)).getCode());
        }
        verify(tasks, never()).transitionAssigneeTaskIfCurrent(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(submissions, never()).insert(any(TaskSubmission.class));
    }
    @Test void resultUrlAcceptsHttpAndHttps() {
        Task task = task("IN_PROGRESS"); task.setAssigneeId(1L);
        when(tasks.selectById(10L)).thenReturn(task);
        when(projects.selectById(4L)).thenReturn(project());
        when(tasks.transitionAssigneeTaskIfCurrent(eq(10L), eq(1L), eq("IN_PROGRESS"), eq("REVIEW"), any())).thenReturn(1);
        when(submissions.selectMaxSubmissionNo(10L)).thenReturn(0);
        SubmitTaskDTO dto = new SubmitTaskDTO(); dto.setCompletionNote("完成"); dto.setTestNote("测试通过");
        dto.setResultUrl("https://example.com/result"); taskService.submitTask(10L, dto);
        task.setStatus("IN_PROGRESS");
        dto.setResultUrl("http://example.com/result"); taskService.submitTask(10L, dto);
        verify(submissions, times(2)).insert(any(TaskSubmission.class));
    }
    @Test void acceptanceFinalUpdateChecksExpiry() {
        String sql = updateSql(ProjectInvitationMapper.class, "transitionPending");
        assertTrue(sql.contains("status = 'PENDING'")); assertTrue(sql.contains("expires_at > #{respondedAt}"));
    }
    @Test void candidateQueryDistinguishesAllStatesAndExcludesAdmins() {
        String sql = selectSql(UserMapper.class, "selectMemberCandidates");
        assertTrue(sql.contains("u.system_role = 'USER'"));
        for (String state : List.of("JOINED", "DISABLED", "PENDING", "REINVITE", "INVITE")) assertTrue(sql.contains("'" + state + "'"));
        assertTrue(sql.indexOf("THEN 'JOINED'") < sql.indexOf("THEN 'DISABLED'"));
        assertTrue(sql.contains("pi.expires_at > #{now}"));
        assertTrue(sql.contains("pm.status = 'INACTIVE'"));
    }
    @Test void managementQueryIncludesDisabledRelationsAndOnlyCountsUnfinishedTasks() {
        String sql = selectSql(ProjectMemberMapper.class, "selectActiveMembers");
        assertTrue(sql.contains("pm.status = 'ACTIVE'"));
        assertFalse(sql.contains("AND u.status = 'ACTIVE'"));
        assertTrue(sql.contains("u.status AS userStatus"));
        assertTrue(sql.contains("AS effective"));
        assertTrue(sql.contains("'TODO', 'IN_PROGRESS', 'REVIEW'"));
        assertFalse(sql.contains("'DONE'")); assertFalse(sql.contains("'CANCELLED'"));
    }
}
