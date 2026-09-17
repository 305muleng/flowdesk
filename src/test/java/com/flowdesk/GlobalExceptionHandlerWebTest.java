package com.flowdesk;

import com.flowdesk.controller.TaskController;
import com.flowdesk.controller.UserController;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.exception.GlobalExceptionHandler;
import com.flowdesk.service.TaskService;
import com.flowdesk.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class GlobalExceptionHandlerWebTest {

    private MockMvc mockMvc;

    private UserService userService;
    private TaskService taskService;


    @BeforeEach
    void setUp() {

        // 1. Service 仍然使用 Mock
        userService = mock(UserService.class);
        taskService = mock(TaskService.class);

        // 2. Controller 使用真的
        UserController userController =
                new UserController(userService);

        TaskController taskController =
                new TaskController(taskService);

        // 3. 建立一个假的 Spring MVC HTTP 环境
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        userController,
                        taskController
                )
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
                .build();
    }


    @Test
    void invalidDtoShouldReturn400() throws Exception {

        mockMvc.perform(
                        post("/users/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "username": "   ",
                                          "password": "123456",
                                          "realName": "Tom"
                                        }
                                        """)
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.code").value(400)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("用户名不能为空")
                );
    }


    @Test
    void invalidPathVariableShouldReturn400()
            throws Exception {

        mockMvc.perform(
                        get("/tasks/abc")
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.code").value(400)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("请求参数类型错误")
                );
    }


    @Test
    void malformedJsonShouldReturn400()
            throws Exception {

        mockMvc.perform(
                        post("/users/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "username": "tom",
                                          "password": "123456",
                                          "realName":
                                        }
                                        """)
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.code").value(400)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("请求体格式错误")
                );
    }


    @Test
    void businessNotFoundShouldReturn404()
            throws Exception {

        when(taskService.getTaskDetail(999L))
                .thenThrow(
                        new BusinessException(
                                404,
                                "任务不存在"
                        )
                );

        mockMvc.perform(
                        get("/tasks/999")
                )
                .andExpect(
                        status().isNotFound()
                )
                .andExpect(
                        jsonPath("$.code").value(404)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("任务不存在")
                );
    }


    @Test
    void businessConflictShouldReturn409()
            throws Exception {

        doThrow(
                new BusinessException(
                        409,
                        "只有待处理任务可以开始"
                )
        )
                .when(taskService)
                .startTask(9L);

        mockMvc.perform(
                        post("/tasks/9/start")
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.code").value(409)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("只有待处理任务可以开始")
                );
    }


    @Test
    void wrongHttpMethodShouldReturn405()
            throws Exception {

        mockMvc.perform(
                        get("/tasks/9/start")
                )
                .andExpect(
                        status().isMethodNotAllowed()
                )
                .andExpect(
                        jsonPath("$.code").value(405)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("请求方法不支持")
                );
    }


    @Test
    void unknownExceptionShouldReturn500()
            throws Exception {

        doThrow(
                new RuntimeException("模拟程序异常")
        )
                .when(taskService)
                .startTask(9L);

        mockMvc.perform(
                        post("/tasks/9/start")
                )
                .andExpect(
                        status().isInternalServerError()
                )
                .andExpect(
                        jsonPath("$.code").value(500)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("服务器内部错误")
                );
    }
}