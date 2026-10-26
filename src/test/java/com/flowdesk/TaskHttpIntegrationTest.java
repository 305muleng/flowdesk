package com.flowdesk;

import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ActiveProfiles("test")
@IntegrationTest
class TaskHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void splitControllersPreserveAuthenticatedTaskQueriesCommentsAndTransitions() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, LocalDateTime.now().plusDays(1).withNano(0));
            String username = jdbc.queryForObject("SELECT username FROM user WHERE id = ?", String.class, f.developer);
            var login = send("POST", "/users/login", null,
                mapper.writeValueAsString(Map.of("username", username, "password", "test-only")));
            assertEquals(200, login.statusCode());
            String token = mapper.readTree(login.body()).get("data").get("token").asString();
            assertEquals(401, send("GET", "/tasks/" + task, null, null).statusCode());
            var detail = send("GET", "/tasks/" + task, token, null);
            assertEquals(200, detail.statusCode());
            assertEquals("TODO", mapper.readTree(detail.body()).get("data").get("status").asString());
            assertEquals(200, send("GET", "/projects/" + f.projectId + "/tasks", token, null).statusCode());
            assertEquals(200, send("GET", "/tasks/my", token, null).statusCode());
            assertEquals(200, send("GET", "/tasks/my/summary", token, null).statusCode());
            assertEquals(400, send("GET", "/tasks/my?status=UNKNOWN", token, null).statusCode());
            assertEquals(200, send("GET", "/tasks/" + task + "/submissions", token, null).statusCode());
            assertEquals(200, send("POST", "/tasks/" + task + "/comments", token, "{\"content\":\"接口回归评论\"}").statusCode());
            var comments = send("GET", "/tasks/" + task + "/comments", token, null);
            assertEquals(200, comments.statusCode());
            assertEquals(1, mapper.readTree(comments.body()).get("data").size());
            assertEquals(200, send("POST", "/tasks/" + task + "/start", token, null).statusCode());
            assertEquals("IN_PROGRESS", jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, task));
            assertEquals(200, send("POST", "/tasks/" + task + "/submit", token,
                "{\"completionNote\":\"已完成\",\"testNote\":\"已验证\",\"resultUrl\":\"https://example.com/result\"}").statusCode());
            assertEquals("REVIEW", jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, task));
        }
    }

    @Test
    void deadlineEndpointChecksRoleStaleValuesAndSubmittedState() throws Exception {
        try (var f = ProjectFixture.create(jdbc)) {
            LocalDateTime old = LocalDateTime.now().plusDays(1).withNano(0);
            LocalDateTime next = old.plusDays(1);
            long task = f.task(f.developer, old);
            jdbc.update("UPDATE task SET status='IN_PROGRESS' WHERE id=?", task);
            String manager = login(f.manager);
            String developer = login(f.developer);
            String body = mapper.writeValueAsString(Map.of("expectedDeadline", old.toString(), "deadline", next.toString(), "reason", "调整交付计划"));
            assertEquals(401, send("PATCH", "/tasks/" + task + "/deadline", null, body).statusCode());
            assertEquals(403, send("PATCH", "/tasks/" + task + "/deadline", developer, body).statusCode());
            assertEquals(200, send("PATCH", "/tasks/" + task + "/deadline", manager, body).statusCode());
            assertEquals(next, jdbc.queryForObject("SELECT deadline FROM task WHERE id=?", LocalDateTime.class, task));
            assertEquals(409, send("PATCH", "/tasks/" + task + "/deadline", manager, body).statusCode());
            assertEquals(400, send("GET", "/tasks/my?projectScope=UNKNOWN", developer, null).statusCode());
            var summary = mapper.readTree(send("GET", "/tasks/my/summary", developer, null).body()).get("data");
            assertEquals(1, summary.get("actionableTaskCount").asInt());
            assertEquals(200, send("POST", "/tasks/" + task + "/submit", developer,
                "{\"completionNote\":\"完成\",\"testNote\":\"已验证\"}").statusCode());
            String afterSubmit = mapper.writeValueAsString(Map.of("expectedDeadline", next.toString(), "deadline", next.plusDays(1).toString(), "reason", "过期页面"));
            assertEquals(409, send("PATCH", "/tasks/" + task + "/deadline", manager, afterSubmit).statusCode());
            summary = mapper.readTree(send("GET", "/tasks/my/summary", developer, null).body()).get("data");
            assertEquals(0, summary.get("actionableTaskCount").asInt());
            assertEquals(1, summary.get("awaitingReviewTaskCount").asInt());
            assertEquals(0, summary.get("dueSoonTaskCount").asInt());
        }
    }

    private String login(long userId) throws Exception {
        String username = jdbc.queryForObject("SELECT username FROM user WHERE id=?", String.class, userId);
        var result = send("POST", "/users/login", null, mapper.writeValueAsString(Map.of("username", username, "password", "test-only")));
        assertEquals(200, result.statusCode());
        return mapper.readTree(result.body()).get("data").get("token").asString();
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
