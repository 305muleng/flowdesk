package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.mapper.*;
import com.flowdesk.service.*;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import com.flowdesk.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "flowdesk.websocket.ticket-prefix=flowdesk:test:ws-integration:",
    "flowdesk.websocket.check-interval-ms=200"})
@ActiveProfiles("test")
@IntegrationTest
class NotificationWebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationService notifications;
    @Autowired ApplicationEventPublisher events;
    @Autowired TaskReminderMapper reminders;
    @Autowired ProjectMapper projects;
    @Autowired TaskMapper tasks;
    @Autowired NotificationMapper notificationMapper;
    @Value("${jwt.secret}") String secret;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<String> issued=new ArrayList<>();
    final List<LiveClient> sockets=new ArrayList<>();
    ProjectFixture fixture;

    @BeforeEach void setup(){fixture=ProjectFixture.create(jdbc);}
    @AfterEach void cleanup() throws Exception {
        try {
            for(var socket:sockets)socket.close();
            for(String ticket:issued)redis.delete("flowdesk:test:ws-integration:"+ticket);
        } finally {UserContext.remove();fixture.close();}
    }

    private HttpResponse<String> http(String method,String path,String token,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String ticket(long user) throws Exception {return ticket(jwt.generateToken(user));}
    private String ticket(String token) throws Exception {
        var response=http("POST","/notifications/ws-ticket",token,null);assertEquals(200,response.statusCode());
        String ticket=json.readTree(response.body()).get("data").get("ticket").asString();issued.add(ticket);return ticket;
    }
    private LiveClient connect(String ticket) throws Exception {
        var socket=new LiveClient();
        socket.socket=client.newWebSocketBuilder().header("Origin","http://localhost:8088").connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/notifications?ticket="+ticket),socket).get(5,TimeUnit.SECONDS);
        sockets.add(socket);
        assertEquals("ready",json.readTree(socket.messages.poll(5,TimeUnit.SECONDS)).get("type").asString());
        return socket;
    }
    private CreateNotificationDTO notice() {
        var dto=new CreateNotificationDTO();dto.setRecipientId(fixture.developer);dto.setActorId(fixture.manager);
        dto.setProjectId(fixture.projectId);dto.setTargetType("PROJECT");dto.setTargetId(fixture.projectId);dto.setType("TASK_ASSIGNED");dto.setTitle("实时通知回归");dto.setContent("测试内容");return dto;
    }

    @Test void commitPushesToEveryRecipientTabButRollbackAndOtherUsersReceiveNothing() throws Exception {
        var first=connect(ticket(fixture.developer));var second=connect(ticket(fixture.developer));var other=connect(ticket(fixture.target));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            notifications.createNotification(notice());
            throw new IllegalStateException("模拟事务回滚");
        }));
        assertNull(first.messages.poll(250,TimeUnit.MILLISECONDS));assertEquals(0,fixture.count("notification"));
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            notifications.createNotification(notice());
            assertTrue(first.messages.isEmpty());
        });
        var message=json.readTree(first.messages.poll(5,TimeUnit.SECONDS));
        assertEquals("notification.created",message.get("type").asString());
        assertEquals(message.get("notificationId").asLong(),json.readTree(second.messages.poll(5,TimeUnit.SECONDS)).get("notificationId").asLong());
        assertNull(other.messages.poll(250,TimeUnit.MILLISECONDS));assertEquals(1,fixture.count("notification"));
    }

    @Test void ticketsAreAuthenticatedSingleUseAndOriginRestricted() throws Exception {
        assertEquals(401,http("POST","/notifications/ws-ticket",null,null).statusCode());
        String used=ticket(fixture.developer);connect(used);
        var replay=assertThrows(ExecutionException.class,()->client.newWebSocketBuilder().header("Origin","http://localhost:8088")
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/notifications?ticket="+used),new LiveClient()).get(5,TimeUnit.SECONDS));
        assertEquals(401,assertInstanceOf(WebSocketHandshakeException.class,replay.getCause()).getResponse().statusCode());
        String fresh=ticket(fixture.target);
        var origin=assertThrows(ExecutionException.class,()->client.newWebSocketBuilder().header("Origin","https://untrusted.invalid")
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/notifications?ticket="+fresh),new LiveClient()).get(5,TimeUnit.SECONDS));
        assertEquals(403,assertInstanceOf(WebSocketHandshakeException.class,origin.getCause()).getResponse().statusCode());
        connect(fresh);
    }

    @Test void concurrentHandshakesCannotReuseOneTicket() throws Exception {
        String shared = ticket(fixture.developer);
        var listeners = List.of(new LiveClient(), new LiveClient());
        var futures = listeners.stream().map(listener -> client.newWebSocketBuilder().header("Origin", "http://localhost:8088")
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/notifications?ticket=" + shared), listener)).toList();
        int accepted = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                listeners.get(i).socket = futures.get(i).get(5, TimeUnit.SECONDS);
                sockets.add(listeners.get(i));
                assertEquals("ready", json.readTree(listeners.get(i).messages.poll(5, TimeUnit.SECONDS)).get("type").asString());
                accepted++;
            } catch (ExecutionException error) {
                assertEquals(401, assertInstanceOf(WebSocketHandshakeException.class, error.getCause()).getResponse().statusCode());
            }
        }
        assertEquals(1, accepted);
    }

    @Test void anExpiredRedisTicketCannotEstablishAConnection() throws Exception {
        String expired = ticket(fixture.developer);
        String key = "flowdesk:test:ws-integration:" + expired;
        assertTrue(redis.getExpire(key) > 0 && redis.getExpire(key) <= 60);
        assertTrue(redis.expire(key, Duration.ofMillis(1)));
        for (int i = 0; i < 20 && Boolean.TRUE.equals(redis.hasKey(key)); i++) Thread.sleep(10);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        var failure = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder().header("Origin", "http://localhost:8088")
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/notifications?ticket=" + expired), new LiveClient()).get(5, TimeUnit.SECONDS));
        assertEquals(401, assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse().statusCode());
    }

    @Test void offlineNotificationCanBeReadAfterReconnecting() throws Exception {
        var first=connect(ticket(fixture.developer));first.close();
        new TransactionTemplate(transactions).executeWithoutResult(tx->notifications.createNotification(notice()));
        connect(ticket(fixture.developer));
        var response=http("GET","/notifications?readFilter=UNREAD",jwt.generateToken(fixture.developer),null);
        assertEquals(200,response.statusCode());assertEquals(1,json.readTree(response.body()).get("data").get("total").asInt());
    }

    @Test void disablingAnAccountClosesItsConnections() throws Exception {
        var socket=connect(ticket(fixture.developer));
        long admin=fixture.insert("INSERT INTO user(username,password_hash,real_name,system_role,status) VALUES (?,'test','测试管理员','SYSTEM_ADMIN','ACTIVE')","ws_admin_"+UUID.randomUUID());
        fixture.users.add(admin);
        assertEquals(200,http("PUT","/admin/users/"+fixture.developer+"/disable",jwt.generateToken(admin),null).statusCode());
        assertEquals(4003,socket.closed.get(5,TimeUnit.SECONDS));
    }

    @Test void tokenExpirationClosesTheConnection() throws Exception {
        String shortToken=new JwtUtil(secret,3000).generateToken(fixture.developer);
        var socket=connect(ticket(shortToken));
        assertEquals(4001,socket.closed.get(6,TimeUnit.SECONDS));
    }

    @Test void reminderDirectInsertAlsoPublishesOnlyOnce() throws Exception {
        var socket=connect(ticket(fixture.developer));
        var factory=new ProxyFactory(new TaskReminderService(reminders,projects,tasks,notificationMapper,events,true));
        factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        var service=(TaskReminderService)factory.getProxy();
        long task=fixture.task(fixture.developer,LocalDateTime.now().plusHours(12).withNano(0));
        service.planTask(task,LocalDateTime.now());
        long event=jdbc.queryForObject("SELECT id FROM task_reminder_event WHERE task_id=?",Long.class,task);
        service.deliver(event);
        assertEquals("notification.created",json.readTree(socket.messages.poll(5,TimeUnit.SECONDS)).get("type").asString());
        service.deliver(event);assertNull(socket.messages.poll(250,TimeUnit.MILLISECONDS));assertEquals(1,fixture.count("notification"));
    }

    private static class LiveClient implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> messages=new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();
        final StringBuilder text=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){
            text.append(data);if(last){messages.add(text.toString());text.setLength(0);}socket.request(1);return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket socket,ByteBuffer data){socket.request(1);return socket.sendPong(data);}
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason){closed.complete(status);return null;}
        void close() throws Exception {if(socket!=null&&!closed.isDone()){socket.sendClose(1000,"测试结束").get(3,TimeUnit.SECONDS);closed.get(3,TimeUnit.SECONDS);}}
    }
}
