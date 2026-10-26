package com.flowdesk;

import com.flowdesk.controller.TaskController;
import com.flowdesk.controller.TaskQueryController;
import com.flowdesk.controller.TaskCommentController;
import com.flowdesk.dto.HandoverTaskDTO;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.TaskService;
import com.flowdesk.service.TaskQueryService;
import com.flowdesk.service.TaskCommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskHandoverWebTest {
    TaskCommentService taskComments = org.mockito.Mockito.mock(TaskCommentService.class);
    TaskQueryService taskQueries = org.mockito.Mockito.mock(TaskQueryService.class);

    TaskService service = mock(TaskService.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new com.flowdesk.controller.TaskQueryController(taskQueries), new com.flowdesk.controller.TaskCommentController(taskComments), new TaskController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void validRequestBindsExpectedAssigneeAndReason() throws Exception {
        mvc.perform(
            post("/tasks/10/handover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAssigneeId\":2,\"assigneeId\":3,\"reason\":\"账号禁用\"}")
        ).andExpect(status().isOk());
        verify(service).handoverDisabledAssignee(10L, new HandoverTaskDTO(2L, 3L, "账号禁用"));
    }

    @Test
    void blankMissingOrOversizedFieldsAreRejectedBeforeService() throws Exception {
        for (String body : new String[] {
            "{\"expectedAssigneeId\":2,\"assigneeId\":3,\"reason\":\"  \"}",
            "{\"assigneeId\":3,\"reason\":\"交接\"}",
            "{\"expectedAssigneeId\":2,\"reason\":\"交接\"}",
            "{\"expectedAssigneeId\":2,\"assigneeId\":3,\"reason\":\"" + "字".repeat(501) + "\"}"
        }) {
            mvc.perform(post("/tasks/10/handover").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(
                status().isBadRequest()
            );
        }
        verifyNoInteractions(service);
    }
}
