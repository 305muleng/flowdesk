package com.flowdesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskSubmission;
import com.flowdesk.service.NotificationService;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private ProjectPermissionService projectPermissionService;

    @Mock
    private TaskSubmissionMapper taskSubmissionMapper;

    @Mock
    private TaskCommentMapper taskCommentMapper;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private TaskService taskService;

    @Mock
    private NotificationService notificationService;

    @AfterEach
    void cleanUp() {
        UserContext.remove();
    }

    @Test
    void creatingTaskWithAssigneeNotifiesAssignee() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        ProjectMember assignee = new ProjectMember();
        assignee.setProjectId(4L);
        assignee.setUserId(2L);
        assignee.setStatus("ACTIVE");

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setTitle("登录接口");
        dto.setAssigneeId(2L);
        dto.setPriority("HIGH");
        dto.setDeadline(LocalDateTime.now().plusDays(1));

        UserContext.set(new CurrentUser(1L, "USER"));

        when(projectMapper.selectById(4L)).thenReturn(project);
        when(projectMemberMapper.selectOne(any())).thenReturn(assignee);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(9L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        Long taskId = taskService.createTask(4L, dto);

        assertEquals(9L, taskId);

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(CreateNotificationDTO.class);

        verify(notificationService).createNotification(captor.capture());

        CreateNotificationDTO notification = captor.getValue();
        assertEquals(2L, notification.getRecipientId());
        assertEquals(1L, notification.getActorId());
        assertEquals("TASK_ASSIGNED", notification.getType());
        assertEquals(4L, notification.getProjectId());
        assertEquals("TASK", notification.getTargetType());
        assertEquals(9L, notification.getTargetId());
    }

    @Test
    void creatingTaskAssignedToCreatorDoesNotNotifyCreator() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        ProjectMember creator = new ProjectMember();
        creator.setProjectId(4L);
        creator.setUserId(1L);
        creator.setStatus("ACTIVE");

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setTitle("发布检查");
        dto.setAssigneeId(1L);
        dto.setDeadline(LocalDateTime.now().plusDays(1));

        UserContext.set(new CurrentUser(1L, "USER"));

        when(projectMapper.selectById(4L)).thenReturn(project);
        when(projectMemberMapper.selectOne(any())).thenReturn(creator);

        taskService.createTask(4L, dto);

        verify(notificationService, never())
                .createNotification(any(CreateNotificationDTO.class));
    }

    @Test
    void assigningTaskToCurrentManagerDoesNotNotifyManager() {

        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setStatus("TODO");
        task.setTitle("发布检查");

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        ProjectMember manager = new ProjectMember();
        manager.setProjectId(4L);
        manager.setUserId(1L);
        manager.setStatus("ACTIVE");

        AssignTaskDTO dto = new AssignTaskDTO();
        dto.setAssigneeId(1L);

        UserContext.set(new CurrentUser(1L, "USER"));

        when(taskMapper.selectById(9L)).thenReturn(task);
        when(projectMapper.selectById(4L)).thenReturn(project);
        when(projectMemberMapper.selectOne(any())).thenReturn(manager);

        taskService.assignTask(9L, dto);

        verify(taskMapper).updateById(task);
        verify(notificationService, never())
                .createNotification(any(CreateNotificationDTO.class));
    }

    @Test
    void todoTaskCanBeAssignedToActiveMember() {

        // 1. 准备一个 TODO 任务
        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setStatus("TODO");
        task.setAssigneeId(null);
        task.setTitle("登录接口");

        // 2. 准备一个正在进行中的项目
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        // 3. 准备一个有效项目成员
        ProjectMember member = new ProjectMember();
        member.setProjectId(4L);
        member.setUserId(2L);
        member.setStatus("ACTIVE");

        // 4. 模拟当前登录用户 Tom
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        // 5. 前端想把任务分给 Jack
        AssignTaskDTO dto = new AssignTaskDTO();
        dto.setAssigneeId(2L);

        // 6. 告诉 Mock：
        // 查询任务9时，返回我们上面准备好的 task
        when(taskMapper.selectById(9L))
                .thenReturn(task);

        // 查询项目4时，返回 project
        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 查询项目成员时，返回 Jack
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member);

        // 7. 真正调用被测试的方法
        taskService.assignTask(9L, dto);

        // 8. 验证任务负责人真的变成了 Jack
        assertEquals(
                2L,
                task.getAssigneeId()
        );

        // 9. 验证确实执行了数据库更新
        verify(taskMapper)
                .updateById(task);

        // 10. 验证确实写了操作日志
        verify(operationLogService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(CreateNotificationDTO.class);

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notificationDTO =
                captor.getValue();

        assertEquals(
                2L,
                notificationDTO.getRecipientId()
        );

        assertEquals(
                1L,
                notificationDTO.getActorId()
        );

        assertEquals(
                "TASK_ASSIGNED",
                notificationDTO.getType()
        );

        assertEquals(
                "TASK",
                notificationDTO.getTargetType()
        );

        assertEquals(
                9L,
                notificationDTO.getTargetId()
        );
    }

    @Test
    void archivedProjectCannotAssignTask() {

        // 1. 准备一个 TODO 任务
        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setStatus("TODO");

        // 2. 准备一个已经归档的项目
        Project project = new Project();
        project.setId(4L);
        project.setStatus("ARCHIVED");

        // 3. 前端准备把任务分给 Jack
        AssignTaskDTO dto = new AssignTaskDTO();
        dto.setAssigneeId(2L);

        // 4. 模拟查询结果
        when(taskMapper.selectById(9L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 5. 调用 assignTask() 时，应该抛 BusinessException
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskService.assignTask(9L, dto)
                );

        // 6. 验证异常信息
        assertEquals(
                "当前项目状态不允许分配任务负责人",
                exception.getMessage()
        );
    }

    @Test
    void nonTodoTaskCannotBeAssigned() {

        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setStatus("IN_PROGRESS");

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        AssignTaskDTO dto = new AssignTaskDTO();
        dto.setAssigneeId(2L);

        when(taskMapper.selectById(9L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskService.assignTask(9L, dto)
                );

        assertEquals(
                "只有待处理任务可以分配负责人",
                exception.getMessage()
        );
    }

    @Test
    void doneTaskCannotBeCancelled() {

        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setStatus("DONE");

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        CancelTaskDTO dto = new CancelTaskDTO();
        dto.setReason("测试已完成任务不能取消");

        when(taskMapper.selectById(9L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskService.cancelTask(9L, dto)
                );

        assertEquals(
                "只有待处理或进行中的任务可以取消",
                exception.getMessage()
        );
    }

    @Test
    void firstSubmissionShouldUseSubmissionNoOne() {

        // 1. 准备一个正在进行中的任务
        Task task = new Task();
        task.setId(10L);
        task.setProjectId(4L);
        task.setAssigneeId(2L);
        task.setStatus("IN_PROGRESS");

        // 2. 准备一个正在进行中的项目
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        // 3. 模拟前端提交的数据
        SubmitTaskDTO dto = new SubmitTaskDTO();
        dto.setCompletionNote("完成登录功能");
        dto.setResultUrl("https://github.com/example/pr/1");
        dto.setTestNote("本地测试通过");

        // 4. 当前登录用户是 userId=2
        // 同时也是这个任务的负责人
        UserContext.set(
                new CurrentUser(
                        2L,
                        "USER"
                )
        );

        // 5. Service 查询任务时，让 Mock 返回我们准备的 task
        when(taskMapper.selectById(10L))
                .thenReturn(task);

        // 6. Service 查询项目时，让 Mock 返回我们准备的 project
        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 7. 模拟第一次提交
        // SQL 中 COALESCE(MAX(submission_no), 0) 应该返回 0
        when(taskSubmissionMapper.selectMaxSubmissionNo(10L))
                .thenReturn(0);

        // 8. 模拟数据库插入后生成 id
        doAnswer(invocation -> {

            TaskSubmission submission =
                    invocation.getArgument(0);

            submission.setId(100L);

            return 1;

        }).when(taskSubmissionMapper)
                .insert(any(TaskSubmission.class));

        // 9. 真正执行我们要测试的 Service 方法
        Long submissionId =
                taskService.submitTask(
                        10L,
                        dto
                );

        // 10. 抓住 Service 真正传给 insert() 的 TaskSubmission
        ArgumentCaptor<TaskSubmission> captor =
                ArgumentCaptor.forClass(
                        TaskSubmission.class
                );

        verify(taskSubmissionMapper)
                .insert(captor.capture());

        TaskSubmission savedSubmission =
                captor.getValue();

        // 11. 检查生成的提交记录
        assertEquals(100L, submissionId);

        assertEquals(
                1,
                savedSubmission.getSubmissionNo()
        );

        assertEquals(
                10L,
                savedSubmission.getTaskId()
        );

        assertEquals(
                2L,
                savedSubmission.getSubmitterId()
        );

        assertEquals(
                "PENDING",
                savedSubmission.getReviewStatus()
        );

        // 12. 提交以后任务应该进入 REVIEW
        assertEquals(
                "REVIEW",
                task.getStatus()
        );

        verify(taskMapper)
                .updateById(task);
    }

    @Test
    void nonAssigneeCannotStartTask() {

        // 1. 准备一个待开始的任务，负责人是 userId=2
        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setAssigneeId(2L);
        task.setStatus("TODO");

        // 2. 项目正在进行
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        // 3. 当前登录的是 userId=3，不是任务负责人
        UserContext.set(
                new CurrentUser(3L, "USER")
        );

        when(taskMapper.selectById(9L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 4. 执行开始任务，应该被拒绝
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskService.startTask(9L)
                );

        // 5. 应该是权限不足 403
        assertEquals(
                403,
                exception.getCode()
        );

        assertEquals(
                "只有任务负责人可以开始该任务",
                exception.getMessage()
        );

        // 6. 既然失败了，就不能更新任务
        verify(taskMapper, never())
                .updateById(any(Task.class));
    }

    @Test
    void doneTaskCannotBeSubmittedAgain() {

        // 1. 任务已经完成
        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setAssigneeId(2L);
        task.setStatus("DONE");

        // 2. 项目仍处于进行中
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        // 3. 当前登录用户就是任务负责人
        UserContext.set(
                new CurrentUser(2L, "USER")
        );

        SubmitTaskDTO dto = new SubmitTaskDTO();
        dto.setCompletionNote("再次提交");
        dto.setTestNote("测试");

        when(taskMapper.selectById(9L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 4. 再次提交应该失败
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskService.submitTask(9L, dto)
                );

        // 5. 用户有权限，但是任务当前状态冲突，所以是 409
        assertEquals(
                409,
                exception.getCode()
        );

        assertEquals(
                "只有进行中的任务可以提交审核",
                exception.getMessage()
        );

        // 6. 不应该产生新的提交记录
        verify(taskSubmissionMapper, never())
                .insert(any(TaskSubmission.class));
    }

    @Test
    void assigningTaskToInactiveProjectMemberShouldReturn409() {

        // 1. 准备任务
        Task task = new Task();
        task.setId(9L);
        task.setProjectId(4L);
        task.setStatus("TODO");

        // 2. 准备项目
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        // 3. 准备分配任务的 DTO
        AssignTaskDTO dto = new AssignTaskDTO();
        dto.setAssigneeId(99L);

        // 4. Mock：能查到任务
        when(taskMapper.selectById(9L))
                .thenReturn(task);

        // 5. Mock：能查到项目
        when(projectMapper.selectById(4L))
                .thenReturn(project);

        /*
         * 这里不需要再模拟“当前用户是不是项目负责人”。
         *
         * 因为 projectPermissionService 在这个测试里本身就是 Mock。
         * requireProjectManager(...) 是 void 方法，
         * Mockito 默认什么都不做，相当于权限检查直接通过。
         */

        // 6. Mock：要被分配的 userId=99 不是项目有效成员
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(null);

        // 7. 执行，并期待抛出 BusinessException
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskService.assignTask(9L, dto)
                );

        // 8. 检查状态码
        assertEquals(
                409,
                exception.getCode()
        );

        // 9. 检查错误信息
        assertEquals(
                "任务负责人不是该项目的有效成员",
                exception.getMessage()
        );

        // 10. 分配失败后，不应该更新任务
        verify(taskMapper, never())
                .updateById(any(Task.class));
    }

    @Test
    void submittingTaskNotifiesProjectManager() {

        Task task = new Task();
        task.setId(10L);
        task.setProjectId(4L);
        task.setTitle("实现登录接口");
        task.setAssigneeId(2L);
        task.setStatus("IN_PROGRESS");

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        ProjectMember manager = new ProjectMember();
        manager.setProjectId(4L);
        manager.setUserId(1L);
        manager.setRole("PROJECT_MANAGER");
        manager.setStatus("ACTIVE");

        SubmitTaskDTO dto = new SubmitTaskDTO();
        dto.setCompletionNote("登录功能已完成");
        dto.setResultUrl("https://github.com/example/pr/1");
        dto.setTestNote("测试通过");

        // Jack 提交任务
        UserContext.set(
                new CurrentUser(2L, "USER")
        );

        when(taskMapper.selectById(10L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskSubmissionMapper.selectMaxSubmissionNo(10L))
                .thenReturn(0);

        when(projectMemberMapper.selectList(any()))
                .thenReturn(List.of(manager));

        doAnswer(invocation -> {
            TaskSubmission submission =
                    invocation.getArgument(0);

            submission.setId(100L);

            return 1;
        }).when(taskSubmissionMapper)
                .insert(any(TaskSubmission.class));

        taskService.submitTask(10L, dto);

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        // Tom 收通知
        assertEquals(
                1L,
                notification.getRecipientId()
        );

        // Jack 是触发者
        assertEquals(
                2L,
                notification.getActorId()
        );

        assertEquals(
                "TASK_SUBMISSION_SUBMITTED",
                notification.getType()
        );

        assertEquals(
                "TASK",
                notification.getTargetType()
        );

        assertEquals(
                10L,
                notification.getTargetId()
        );
    }

    @Test
    void approvingTaskSubmissionNotifiesAssignee() {

        Task task = new Task();
        task.setId(10L);
        task.setProjectId(4L);
        task.setTitle("实现登录接口");
        task.setAssigneeId(2L);
        task.setStatus("REVIEW");

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        TaskSubmission submission = new TaskSubmission();
        submission.setId(100L);
        submission.setTaskId(10L);
        submission.setSubmissionNo(1);
        submission.setReviewStatus("PENDING");

        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setAction("APPROVE");
        dto.setReviewNote("实现正确");

        // Tom 审核
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(taskMapper.selectById(10L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskSubmissionMapper.selectPendingSubmission(10L))
                .thenReturn(submission);

        taskService.reviewTask(10L, dto);

        // 顺便确认业务状态真的改了
        assertEquals(
                "DONE",
                task.getStatus()
        );

        assertEquals(
                "APPROVED",
                submission.getReviewStatus()
        );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        // Jack 收通知
        assertEquals(
                2L,
                notification.getRecipientId()
        );

        // Tom 是审核人
        assertEquals(
                1L,
                notification.getActorId()
        );

        assertEquals(
                "TASK_SUBMISSION_APPROVED",
                notification.getType()
        );

        assertEquals(
                "TASK",
                notification.getTargetType()
        );

        assertEquals(
                10L,
                notification.getTargetId()
        );
    }

    @Test
    void rejectingTaskSubmissionNotifiesAssignee() {

        Task task = new Task();
        task.setId(10L);
        task.setProjectId(4L);
        task.setTitle("实现登录接口");
        task.setAssigneeId(2L);
        task.setStatus("REVIEW");

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        TaskSubmission submission = new TaskSubmission();
        submission.setId(100L);
        submission.setTaskId(10L);
        submission.setSubmissionNo(1);
        submission.setReviewStatus("PENDING");

        ReviewTaskDTO dto = new ReviewTaskDTO();
        dto.setAction("REJECT");
        dto.setReviewNote("测试覆盖不足");

        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(taskMapper.selectById(10L))
                .thenReturn(task);

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskSubmissionMapper.selectPendingSubmission(10L))
                .thenReturn(submission);

        taskService.reviewTask(10L, dto);

        assertEquals(
                "IN_PROGRESS",
                task.getStatus()
        );

        assertEquals(
                "REJECTED",
                submission.getReviewStatus()
        );

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        assertEquals(
                2L,
                notification.getRecipientId()
        );

        assertEquals(
                1L,
                notification.getActorId()
        );

        assertEquals(
                "TASK_SUBMISSION_REJECTED",
                notification.getType()
        );

        assertEquals(
                10L,
                notification.getTargetId()
        );
    }
}
