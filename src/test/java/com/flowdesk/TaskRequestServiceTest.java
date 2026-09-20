package com.flowdesk;

import  com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.dto.CreateTaskRequestDTO;
import com.flowdesk.dto.ReviewTaskRequestDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.ProjectMember;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.model.User;
import com.flowdesk.service.NotificationService;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.service.TaskRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TaskRequestServiceTest {

    @Mock
    private TaskRequestMapper taskRequestMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectPermissionService projectPermissionService;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @InjectMocks
    private TaskRequestService taskRequestService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserMapper userMapper;

    @BeforeEach
    void setUpActiveUsers() {
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

    /**
     * 已归档项目不能继续审批旧的任务申请
     */
    @Test
    void archivedProjectCannotReviewTaskRequest() {

        // 1. 准备一个已经归档的项目
        Project project = new Project();
        project.setId(4L);
        project.setStatus("ARCHIVED");

        // 2. 准备审批参数
        ReviewTaskRequestDTO dto =
                new ReviewTaskRequestDTO();

        dto.setAction("APPROVE");

        // 3. 模拟查询项目
        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 4. 调用审批方法
        // 我们期待这里抛出 BusinessException
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskRequestService.reviewTaskRequest(
                                4L,
                                1L,
                                dto
                        )
                );

        // 5. 检查错误信息
        assertEquals(
                "当前项目状态不允许审批任务申请",
                exception.getMessage()
        );
    }

    /**
     * 正常批准任务申请后，应当创建正式 Task
     */
    @Test
    void approvingTaskRequestCreatesFormalTask() {

        // 1. 准备一个正在进行中的项目
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        // 2. 准备 Jack 提交的任务申请
        TaskRequest request = new TaskRequest();

        request.setId(1L);
        request.setProjectId(4L);
        request.setRequesterId(2L);

        request.setTitle("增加项目统计功能");
        request.setDescription("统计项目中的任务数量");
        request.setGoal("查看项目任务完成情况");

        request.setStatus("PENDING");

        // 3. 准备 Tom 的审批参数
        ReviewTaskRequestDTO dto =
                new ReviewTaskRequestDTO();

        dto.setAction("APPROVE");
        dto.setReviewNote("同意开发");
        dto.setPriority("MEDIUM");

        dto.setDeadline(
                LocalDateTime.now().plusDays(7)
        );

        // 这里暂时不指定 assigneeId
        // 这样这个测试只关注“审批后创建正式任务”
        dto.setAssigneeId(null);

        // 4. 模拟当前登录用户 Tom
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        // 5. 模拟 Mapper 查询结果
        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskRequestMapper.selectById(1L))
                .thenReturn(request);

        /*
         * 6. 模拟数据库插入 Task 后生成主键
         *
         * 真实 MyBatis：
         * taskMapper.insert(task)
         * 之后 task.getId() 会得到数据库生成的 id。
         *
         * 但现在 taskMapper 是假的 Mock，
         * 所以我们手动模拟数据库生成 id = 99。
         */
        doAnswer(invocation -> {

            Task task =
                    invocation.getArgument(0);

            task.setId(99L);

            return 1;

        }).when(taskMapper)
                .insert(any(Task.class));

        // 7. 真正调用审批方法
        Long taskId =
                taskRequestService.reviewTaskRequest(
                        4L,
                        1L,
                        dto
                );

        // 8. Service 应该返回新任务 id
        assertEquals(
                99L,
                taskId
        );

        /*
         * 9. 抓住 taskMapper.insert(...) 时
         * 真正传进去的那个 Task
         */
        ArgumentCaptor<Task> taskCaptor =
                ArgumentCaptor.forClass(Task.class);

        verify(taskMapper)
                .insert(taskCaptor.capture());

        Task createdTask =
                taskCaptor.getValue();

        // 10. 检查正式 Task
        assertEquals(
                4L,
                createdTask.getProjectId()
        );

        /*
         * 这里非常重要：
         *
         * Jack(id=2) 是提出任务申请的人
         * Tom(id=1) 是审批并正式创建任务的人
         *
         * 所以 creatorId 必须是 Tom = 1
         */
        assertEquals(
                1L,
                createdTask.getCreatorId()
        );

        assertEquals(
                "TODO",
                createdTask.getStatus()
        );

        assertEquals(
                "MEDIUM",
                createdTask.getPriority()
        );

        assertEquals(
                "增加项目统计功能",
                createdTask.getTitle()
        );

        assertEquals(
                "统计项目中的任务数量",
                createdTask.getDescription()
        );

        assertEquals(
                "查看项目任务完成情况",
                createdTask.getGoal()
        );

        // 11. 检查原来的 TaskRequest
        assertEquals(
                "APPROVED",
                request.getStatus()
        );

        assertEquals(
                1L,
                request.getReviewerId()
        );

        assertEquals(
                "同意开发",
                request.getReviewNote()
        );

        assertEquals(
                99L,
                request.getTaskId()
        );

        // 12. 确认原任务申请确实执行了数据库更新
        verify(taskRequestMapper)
                .updateById(request);
    }

    @Test
    void developerCannotReviewTaskRequest() {

        ReviewTaskRequestDTO dto =
                new ReviewTaskRequestDTO();

        dto.setAction("APPROVE");

        doThrow(
                new BusinessException(
                        403,
                        "你不是该项目负责人"
                )
        )
                .when(projectPermissionService)
                .requireProjectManager(4L);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> taskRequestService
                                .reviewTaskRequest(
                                        4L,
                                        7L,
                                        dto
                                )
                );

        assertEquals(
                403,
                exception.getCode()
        );

        assertEquals(
                "你不是该项目负责人",
                exception.getMessage()
        );
    }

    @Test
    void creatingTaskRequestNotifiesProjectManager() {

        // Jack 是当前项目的开发人员
        ProjectMember developer = new ProjectMember();
        developer.setProjectId(4L);
        developer.setUserId(2L);
        developer.setRole("DEVELOPER");
        developer.setStatus("ACTIVE");

        when(projectPermissionService.requireProjectMember(4L))
                .thenReturn(developer);

        // 项目允许提交任务申请
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        // 当前登录用户是 Jack
        UserContext.set(
                new CurrentUser(2L, "USER")
        );

        // 创建申请参数
        CreateTaskRequestDTO dto =
                new CreateTaskRequestDTO();

        dto.setTitle("增加项目统计功能");
        dto.setDescription("统计任务数量");
        dto.setGoal("查看项目进度");

        // 模拟插入后数据库生成 TaskRequest id = 15
        doAnswer(invocation -> {

            TaskRequest request =
                    invocation.getArgument(0);

            request.setId(15L);

            return 1;

        }).when(taskRequestMapper)
                .insert(any(TaskRequest.class));

        // Tom 是项目负责人
        ProjectMember manager = new ProjectMember();
        manager.setProjectId(4L);
        manager.setUserId(1L);
        manager.setRole("PROJECT_MANAGER");
        manager.setStatus("ACTIVE");

        when(projectMemberMapper.selectList(any()))
                .thenReturn(List.of(manager));

        // 真正执行
        Long requestId =
                taskRequestService.createTaskRequest(
                        4L,
                        dto
                );

        assertEquals(15L, requestId);

        // 抓住传给 NotificationService 的 DTO
        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService)
                .createNotification(captor.capture());

        CreateNotificationDTO notification =
                captor.getValue();

        // 收件人：Tom
        assertEquals(
                1L,
                notification.getRecipientId()
        );

        // 操作者：Jack
        assertEquals(
                2L,
                notification.getActorId()
        );

        assertEquals(
                "TASK_REQUEST_SUBMITTED",
                notification.getType()
        );

        assertEquals(
                "TASK_REQUEST",
                notification.getTargetType()
        );

        assertEquals(
                15L,
                notification.getTargetId()
        );

        assertEquals(
                4L,
                notification.getProjectId()
        );
    }

    @Test
    void disabledManagerDoesNotReceiveTaskRequestNotification() {
        ProjectMember developer = new ProjectMember();
        developer.setRole("DEVELOPER");
        when(projectPermissionService.requireProjectMember(4L)).thenReturn(developer);
        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");
        when(projectMapper.selectById(4L)).thenReturn(project);
        ProjectMember manager = new ProjectMember();
        manager.setUserId(1L);
        manager.setRole("PROJECT_MANAGER");
        manager.setStatus("ACTIVE");
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(manager));
        User disabled = new User();
        disabled.setId(1L);
        disabled.setStatus("DISABLED");
        disabled.setSystemRole("USER");
        when(userMapper.selectById(1L)).thenReturn(disabled);
        UserContext.set(new CurrentUser(2L, "USER"));
        CreateTaskRequestDTO dto = new CreateTaskRequestDTO();
        dto.setTitle("增加统计");

        taskRequestService.createTaskRequest(4L, dto);

        verify(notificationService, never()).createNotification(any(CreateNotificationDTO.class));
    }

    @Test
    void rejectingTaskRequestNotifiesRequester() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        TaskRequest request = new TaskRequest();
        request.setId(10L);
        request.setProjectId(4L);
        request.setRequesterId(2L);
        request.setTitle("增加缓存");
        request.setStatus("PENDING");

        ReviewTaskRequestDTO dto =
                new ReviewTaskRequestDTO();

        dto.setAction("REJECT");
        dto.setReviewNote("暂时不需要");

        // Tom 审批
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskRequestMapper.selectById(10L))
                .thenReturn(request);

        Long result =
                taskRequestService.reviewTaskRequest(
                        4L,
                        10L,
                        dto
                );

        // REJECT 不会产生正式 Task
        assertEquals(null, result);

        assertEquals(
                "REJECTED",
                request.getStatus()
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
                "TASK_REQUEST_REJECTED",
                notification.getType()
        );

        assertEquals(
                "TASK_REQUEST",
                notification.getTargetType()
        );

        assertEquals(
                10L,
                notification.getTargetId()
        );
    }

    @Test
    void approvingTaskRequestAndAssigningToRequesterCreatesCombinedNotification() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        TaskRequest request = new TaskRequest();
        request.setId(10L);
        request.setProjectId(4L);
        request.setRequesterId(2L);
        request.setTitle("增加缓存");
        request.setDescription("增加 Redis 缓存");
        request.setGoal("降低数据库压力");
        request.setStatus("PENDING");

        ReviewTaskRequestDTO dto =
                new ReviewTaskRequestDTO();

        dto.setAction("APPROVE");
        dto.setReviewNote("同意");
        dto.setPriority("HIGH");
        dto.setDeadline(
                LocalDateTime.now().plusDays(7)
        );

        // Jack 提的申请，最后也分给 Jack
        dto.setAssigneeId(2L);

        // Tom 审批
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskRequestMapper.selectById(10L))
                .thenReturn(request);

        // Jack 是项目有效成员
        ProjectMember jack = new ProjectMember();
        jack.setProjectId(4L);
        jack.setUserId(2L);
        jack.setStatus("ACTIVE");
        jack.setRole("DEVELOPER");

        when(projectMemberMapper.selectOne(any()))
                .thenReturn(jack);

        // 模拟 Task insert 后生成 id = 99
        doAnswer(invocation -> {

            Task task =
                    invocation.getArgument(0);

            task.setId(99L);

            return 1;

        }).when(taskMapper)
                .insert(any(Task.class));

        Long taskId =
                taskRequestService.reviewTaskRequest(
                        4L,
                        10L,
                        dto
                );

        assertEquals(
                99L,
                taskId
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
                "TASK_REQUEST_APPROVED",
                notification.getType()
        );

        // 申请已经生成正式 Task，所以通知指向 Task
        assertEquals(
                "TASK",
                notification.getTargetType()
        );

        assertEquals(
                99L,
                notification.getTargetId()
        );

        assertEquals(
                "你的任务申请“增加缓存”已通过，并已分配给你",
                notification.getContent()
        );
    }

    @Test
    void approvingTaskRequestAndAssigningToAnotherDeveloperCreatesTwoNotifications() {

        Project project = new Project();
        project.setId(4L);
        project.setStatus("IN_PROGRESS");

        TaskRequest request = new TaskRequest();
        request.setId(10L);
        request.setProjectId(4L);
        request.setRequesterId(2L); // Jack
        request.setTitle("增加缓存");
        request.setDescription("增加 Redis 缓存");
        request.setGoal("降低数据库压力");
        request.setStatus("PENDING");

        ReviewTaskRequestDTO dto =
                new ReviewTaskRequestDTO();

        dto.setAction("APPROVE");
        dto.setReviewNote("同意");
        dto.setPriority("HIGH");
        dto.setDeadline(
                LocalDateTime.now().plusDays(7)
        );

        // 最后把任务分给 Rose，而不是 Jack
        dto.setAssigneeId(3L);

        // Tom 审批
        UserContext.set(
                new CurrentUser(1L, "USER")
        );

        when(projectMapper.selectById(4L))
                .thenReturn(project);

        when(taskRequestMapper.selectById(10L))
                .thenReturn(request);

        // Rose 是有效项目成员
        ProjectMember rose = new ProjectMember();
        rose.setProjectId(4L);
        rose.setUserId(3L);
        rose.setStatus("ACTIVE");
        rose.setRole("DEVELOPER");

        when(projectMemberMapper.selectOne(any()))
                .thenReturn(rose);

        // 模拟生成正式 Task id = 99
        doAnswer(invocation -> {

            Task task = invocation.getArgument(0);

            task.setId(99L);

            return 1;

        }).when(taskMapper)
                .insert(any(Task.class));

        Long taskId =
                taskRequestService.reviewTaskRequest(
                        4L,
                        10L,
                        dto
                );

        assertEquals(99L, taskId);

        ArgumentCaptor<CreateNotificationDTO> captor =
                ArgumentCaptor.forClass(
                        CreateNotificationDTO.class
                );

        verify(notificationService, times(2))
                .createNotification(captor.capture());

        List<CreateNotificationDTO> notifications =
                captor.getAllValues();

        CreateNotificationDTO requesterNotification =
                notifications.get(0);

        CreateNotificationDTO assigneeNotification =
                notifications.get(1);

        // Jack 收到申请通过通知
        assertEquals(
                2L,
                requesterNotification.getRecipientId()
        );

        assertEquals(
                "TASK_REQUEST_APPROVED",
                requesterNotification.getType()
        );

        assertEquals(
                "TASK",
                requesterNotification.getTargetType()
        );

        assertEquals(
                99L,
                requesterNotification.getTargetId()
        );

        // Rose 收到任务分配通知
        assertEquals(
                3L,
                assigneeNotification.getRecipientId()
        );

        assertEquals(
                "TASK_ASSIGNED",
                assigneeNotification.getType()
        );

        assertEquals(
                "TASK",
                assigneeNotification.getTargetType()
        );

        assertEquals(
                99L,
                assigneeNotification.getTargetId()
        );
    }
}
