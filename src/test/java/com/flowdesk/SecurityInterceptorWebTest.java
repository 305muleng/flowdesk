package com.flowdesk;

import com.flowdesk.controller.AdminProjectAcceptanceController;
import com.flowdesk.controller.TaskController;
import com.flowdesk.context.UserContext;
import com.flowdesk.interceptor.LoginInterceptor;
import com.flowdesk.interceptor.SystemAdminInterceptor;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.User;
import com.flowdesk.service.ProjectAcceptanceService;
import com.flowdesk.service.TaskService;
import com.flowdesk.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class SecurityInterceptorWebTest {

    private MockMvc mockMvc;

    private JwtUtil jwtUtil;
    private UserMapper userMapper;

    private TaskService taskService;
    private ProjectAcceptanceService projectAcceptanceService;


    @BeforeEach
    void setUp() {

        // 1. Mock 依赖
        jwtUtil = mock(JwtUtil.class);
        userMapper = mock(UserMapper.class);

        taskService = mock(TaskService.class);
        projectAcceptanceService =
                mock(ProjectAcceptanceService.class);

        // 2. 创建真实 Controller
        TaskController taskController =
                new TaskController(taskService);

        AdminProjectAcceptanceController adminController =
                new AdminProjectAcceptanceController(
                        projectAcceptanceService
                );

        // 3. 创建真实 Interceptor
        LoginInterceptor loginInterceptor =
                new LoginInterceptor(
                        jwtUtil,
                        userMapper
                );

        SystemAdminInterceptor systemAdminInterceptor =
                new SystemAdminInterceptor();

        // 4. 搭建 MockMvc 环境
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        taskController,
                        adminController
                )
                .addInterceptors(
                        loginInterceptor,
                        systemAdminInterceptor
                )
                .build();
    }


    @Test
    void requestWithoutTokenShouldReturn401()
            throws Exception {

        mockMvc.perform(
                        get("/tasks/9")
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }


    @Test
    void invalidTokenShouldReturn401()
            throws Exception {

        when(jwtUtil.parseToken("bad-token"))
                .thenThrow(
                        new RuntimeException(
                                "token invalid"
                        )
                );

        mockMvc.perform(
                        get("/tasks/9")
                                .header(
                                        "Authorization",
                                        "Bearer bad-token"
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }


    @Test
    void normalUserCannotAccessAdminApi()
            throws Exception {

        Claims claims = mock(Claims.class);

        when(jwtUtil.parseToken("user-token"))
                .thenReturn(claims);

        when(claims.getSubject())
                .thenReturn("2");

        User user = new User();
        user.setId(2L);
        user.setStatus("ACTIVE");
        user.setSystemRole("USER");

        when(userMapper.selectById(2L))
                .thenReturn(user);

        mockMvc.perform(
                        get("/admin/project-acceptances")
                                .header(
                                        "Authorization",
                                        "Bearer user-token"
                                )
                )
                .andExpect(
                        status().isForbidden()
                );
    }


    @Test
    void systemAdminCanPassAdminInterceptor()
            throws Exception {

        Claims claims = mock(Claims.class);

        when(jwtUtil.parseToken("admin-token"))
                .thenReturn(claims);

        when(claims.getSubject())
                .thenReturn("1");

        User admin = new User();
        admin.setId(1L);
        admin.setStatus("ACTIVE");
        admin.setSystemRole("SYSTEM_ADMIN");

        when(userMapper.selectById(1L))
                .thenReturn(admin);

        when(projectAcceptanceService
                .getAcceptances("PENDING"))
                .thenReturn(java.util.List.of());

        mockMvc.perform(
                        get("/admin/project-acceptances")
                                .header(
                                        "Authorization",
                                        "Bearer admin-token"
                                )
                )
                .andExpect(
                        status().isOk()
                );
    }

    @Test
    void systemAdminCanAccessAdminApi() throws Exception {

        Claims claims = mock(Claims.class);

        when(jwtUtil.parseToken("admin-token"))
                .thenReturn(claims);

        when(claims.getSubject())
                .thenReturn("1");

        User admin = new User();
        admin.setId(1L);
        admin.setStatus("ACTIVE");
        admin.setSystemRole("SYSTEM_ADMIN");

        when(userMapper.selectById(1L))
                .thenReturn(admin);

        mockMvc.perform(
                        get("/admin/project-acceptances")
                                .header(
                                        "Authorization",
                                        "Bearer admin-token"
                                )
                )
                .andExpect(
                        status().isOk()
                );
    }

    @Test
    void disabledUserShouldReturn403()
            throws Exception {

        Claims claims = mock(Claims.class);

        when(jwtUtil.parseToken("disabled-token"))
                .thenReturn(claims);

        when(claims.getSubject())
                .thenReturn("2");

        User user = new User();
        user.setId(2L);
        user.setStatus("DISABLED");
        user.setSystemRole("USER");

        when(userMapper.selectById(2L))
                .thenReturn(user);

        mockMvc.perform(
                        get("/tasks/9")
                                .header(
                                        "Authorization",
                                        "Bearer disabled-token"
                                )
                )
                .andExpect(
                        status().isForbidden()
                )
                .andExpect(
                        jsonPath("$.code").value(403)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("账号已被禁用")
                );
    }

    @Test
    void tokenForDeletedUserShouldReturn401() throws Exception {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("orphan-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("99");
        when(userMapper.selectById(99L)).thenReturn(null);

        mockMvc.perform(get("/tasks/9")
                        .header("Authorization", "Bearer orphan-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void expiredTokenShouldReturn401() throws Exception {
        when(jwtUtil.parseToken("expired-token"))
                .thenThrow(new RuntimeException("expired"));

        mockMvc.perform(get("/tasks/9")
                        .header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userContextIsClearedAfterCompletedRequest() throws Exception {
        Claims claims = mock(Claims.class);
        when(jwtUtil.parseToken("user-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("2");
        User user = new User();
        user.setId(2L);
        user.setStatus("ACTIVE");
        user.setSystemRole("USER");
        when(userMapper.selectById(2L)).thenReturn(user);

        mockMvc.perform(get("/tasks/9")
                        .header("Authorization", "Bearer user-token"));

        assertNull(UserContext.get());
    }
}
