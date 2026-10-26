package com.flowdesk;

import com.flowdesk.common.PageResult;
import com.flowdesk.controller.*;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.*;
import com.flowdesk.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ListPaginationWebTest {
    TaskCommentService taskComments = org.mockito.Mockito.mock(TaskCommentService.class);
    TaskQueryService taskQueries = org.mockito.Mockito.mock(TaskQueryService.class);

    TaskService tasks = mock(TaskService.class);
    NotificationService notifications = mock(NotificationService.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new com.flowdesk.controller.TaskQueryController(taskQueries), new com.flowdesk.controller.TaskCommentController(taskComments),
            new TaskController(tasks),
            new NotificationController(notifications),
            new ProjectController(
                mock(ProjectService.class),
                mock(ProjectInvitationService.class),
                tasks,
                mock(ProjectTaskSummaryService.class)
            )
        )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void allThreeRoutesBindDefaultPaginationAndReturnPageResult() throws Exception {
        when(taskQueries.getProjectTasks(7L, "ALL", "ALL", 1, 20, null, false)).thenReturn(
            new PageResult<>(List.of(new TaskVO()), 41, 1, 20, 3)
        );
        when(taskQueries.getMyTasks(1, 20, "ALL", null, "CURRENT")).thenReturn(new PageResult<>(List.of(new TaskVO()), 41, 1, 20, 3));
        when(notifications.getMyNotifications(1, 20, "ALL", "ALL")).thenReturn(
            new PageResult<>(List.of(new NotificationVO()), 41, 1, 20, 3)
        );
        for (String route : List.of("/projects/7/tasks", "/tasks/my", "/notifications")) {
            mvc.perform(get(route))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.pages").value(3));
        }
    }

    @Test
    void filtersBindAlongsidePaginationAndSummaryHasNoUserSelector() throws Exception {
        mvc.perform(
            get("/projects/7/tasks")
                .param("page", "2")
                .param("size", "100")
                .param("scope", "MINE")
                .param("status", "DONE")
                .param("assigneeId", "3")
                .param("unfinishedOnly", "true")
        ).andExpect(status().isOk());
        verify(taskQueries).getProjectTasks(7L, "MINE", "DONE", 2, 100, 3L, true);
        mvc.perform(get("/tasks/my").param("status", "TODO").param("keyword", "%_标题")).andExpect(status().isOk());
        verify(taskQueries).getMyTasks(1, 20, "TODO", "%_标题", "CURRENT");
        mvc.perform(get("/notifications").param("readFilter", "READ").param("category", "TASK")).andExpect(status().isOk());
        verify(notifications).getMyNotifications(1, 20, "READ", "TASK");
        when(taskQueries.getMyTaskSummary()).thenReturn(MyTaskSummaryVO.empty());
        mvc.perform(get("/tasks/my/summary").param("userId", "12345"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalTaskCount").value(0));
        verify(taskQueries).getMyTaskSummary(); // 额外参数不传入业务层，身份仅来自 UserContext。
    }

    @Test
    void malformedPaginationAndAssigneeTypesAreClearBadRequests() throws Exception {
        for (String route : List.of("/projects/7/tasks", "/tasks/my", "/notifications")) {
            mvc.perform(get(route).param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数类型错误"));
            mvc.perform(get(route).param("size", "99999999999999999999999")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/projects/7/tasks").param("assigneeId", "other")).andExpect(status().isBadRequest());
        verifyNoInteractions(tasks, notifications);
    }
}
