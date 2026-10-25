package com.flowdesk;

import com.flowdesk.controller.TaskRequestController;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.TaskRequestService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskRequestValidationTest {
    static Stream<Arguments> missingRequiredFields() {
        return Stream.of("description", "goal").flatMap(field ->
                Stream.of("", ",\"" + field + "\":null", ",\"" + field + "\":\"\"",
                                ",\"" + field + "\":\" \\t\\n\"")
                        .map(fragment -> Arguments.of(
                                field.equals("description") ? ",\"goal\":\"明确目标\"" + fragment
                                        : ",\"description\":\"明确描述\"" + fragment,
                                field.equals("description") ? "任务申请描述不能为空" : "任务申请目标不能为空")));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredFields")
    void missingNullEmptyAndWhitespaceFieldsReturn400BeforeService(String fields, String message) throws Exception {
        var service = mock(TaskRequestService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TaskRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/projects/1/task-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"任务申请\"" + fields + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(message));

        // 不合法的请求在入口拒绝，不应进入业务方法产生数据库写入。
        verifyNoInteractions(service);
    }
}
