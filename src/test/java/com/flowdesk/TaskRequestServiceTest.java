package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.ReviewTaskRequestDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskRequestMapper;
import com.flowdesk.model.Project;
import com.flowdesk.model.Task;
import com.flowdesk.model.TaskRequest;
import com.flowdesk.service.ProjectPermissionService;
import com.flowdesk.service.TaskRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

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
}