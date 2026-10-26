package com.flowdesk;

import com.flowdesk.cache.ProjectTaskSummaryCache;
import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.service.*;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class TaskWorkIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskMapper tasks;
    @Autowired ProjectMapper projects;
    @Autowired ProjectMemberMapper members;
    @Autowired TaskSubmissionMapper submissions;
    @Autowired UserMapper users;
    @Autowired NotificationMapper notifications;
    @Autowired TaskReminderMapper reminders;
    @Autowired ProjectPermissionService permissions;
    @Autowired OperationLogService logs;
    @Autowired NotificationService notificationService;
    @Autowired ProjectTaskSummaryCache cache;
    @Autowired TaskQueryService queries;
    @Autowired ProjectAcceptanceService acceptances;
    @Autowired PlatformTransactionManager transactions;
    TaskService service;
    TaskReminderService enabledReminders;

    @BeforeEach
    void setup() {
        // 启用实际提醒事务服务，不启动后台调度，避免测试与扫描线程争用测试数据。
        enabledReminders = proxy(new TaskReminderService(reminders, projects, tasks, notifications, true));
        service = proxy(new TaskService(tasks, projects, members, permissions, submissions, logs,
            notificationService, users, cache, enabledReminders));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @AfterEach
    void clearContext() { UserContext.remove(); }

    private void as(long id) { UserContext.set(new CurrentUser(id, "USER")); }
    private LocalDateTime future(int hours) { return LocalDateTime.now().plusHours(hours).withNano(0); }
    private void state(long task, String state) { jdbc.update("UPDATE task SET status=? WHERE id=?", state, task); }
    private LocalDateTime deadline(long task) {
        return jdbc.queryForObject("SELECT deadline FROM task WHERE id=?", LocalDateTime.class, task);
    }

    @ParameterizedTest
    @ValueSource(strings={"PREPARING", "IN_PROGRESS", "PENDING_ACCEPTANCE", "COMPLETED", "ARCHIVED", "CANCELLED"})
    void projectStateControlsWorkButPreservesTaskHistory(String projectState) {
        try (var f=ProjectFixture.create(jdbc)) {
            for (String status : new String[]{"TODO", "IN_PROGRESS", "REVIEW", "DONE", "CANCELLED"}) {
                long task=f.task(f.developer, future(12)); state(task,status);
            }
            jdbc.update("UPDATE project SET status=? WHERE id=?",projectState,f.projectId);
            as(f.developer);
            var summary=queries.getMyTaskSummary();
            assertEquals(5,summary.totalTaskCount());
            assertEquals(projectState.equals("IN_PROGRESS") ? 2 : 0,summary.actionableTaskCount());
            assertEquals(projectState.equals("IN_PROGRESS") ? 1 : 0,summary.awaitingReviewTaskCount());
            assertEquals(projectState.equals("IN_PROGRESS") ? 2 : 0,summary.dueSoonTaskCount());
            boolean history=java.util.Set.of("COMPLETED","ARCHIVED","CANCELLED").contains(projectState);
            assertEquals(history ? 0 : 5,queries.getMyTasks(1,20,"ALL",null,"CURRENT").getTotal());
            assertEquals(history ? 5 : 0,queries.getMyTasks(1,20,"ALL",null,"HISTORY").getTotal());
            var all=queries.getMyTasks(1,20,"ALL",null,"ALL");
            assertEquals(5,all.getTotal());
            assertTrue(all.getRecords().stream().allMatch(t->projectState.equals(t.getProjectStatus()) && t.getProjectName()!=null));
            assertEquals(1,all.getRecords().stream().filter(t->"TODO".equals(t.getStatus())).count());
        }
    }

    @Test
    void actionableQueryFindsEarliestWorkBeyondRecentHistoryAndCountsOnlyAccessibleRows() {
        try (var f=ProjectFixture.create(jdbc)) {
            long first=f.task(f.developer,future(2));
            f.task(f.developer,future(8));
            for(int i=0;i<25;i++) state(f.task(f.developer,future(1)),"DONE");
            as(f.developer);
            var page=queries.getMyTasks(1,1,"ALL",null,"ACTIONABLE");
            assertEquals(2,page.getTotal()); assertEquals(first,page.getRecords().get(0).getId());
            as(f.target); assertEquals(0,queries.getMyTaskSummary().totalTaskCount());
            as(f.developer);
            jdbc.update("UPDATE project_member SET status='INACTIVE' WHERE project_id=? AND user_id=?",f.projectId,f.developer);
            assertEquals(0,queries.getMyTaskSummary().totalTaskCount());
            assertEquals(0,queries.getMyTasks(1,20,"ALL",null,"ALL").getTotal());
        }
    }

    @Test
    void deadlineChangeUpdatesPlanAndAuditAndInvalidatesOldEvent() {
        try(var f=ProjectFixture.create(jdbc)) {
            long task=f.task(f.developer,future(10)); state(task,"IN_PROGRESS");
            enabledReminders.planTask(task,LocalDateTime.now());
            long old=jdbc.queryForObject("SELECT id FROM task_reminder_event WHERE task_id=?",Long.class,task);
            var original=deadline(task); var next=future(20); as(f.manager);
            service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(original,next,"  排期调整  "));
            assertEquals(next,deadline(task));
            assertEquals(2L,jdbc.queryForObject("SELECT revision FROM task_reminder_plan WHERE task_id=?",Long.class,task));
            enabledReminders.deliver(old);
            assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM task_reminder_event WHERE id=?",String.class,old));
            assertEquals(0,f.count("notification"));
            assertTrue(jdbc.queryForObject("SELECT after_data FROM operation_log WHERE target_id=? AND action='ADJUST_TASK_DEADLINE'",String.class,task).contains("排期调整"));
            assertEquals(f.manager,jdbc.queryForObject("SELECT actor_id FROM operation_log WHERE target_id=?",Long.class,task));
            assertEquals(409,assertThrows(BusinessException.class,()->service.adjustTaskDeadline(task,
                new AdjustTaskDeadlineDTO(original,future(22),"旧页面请求"))).getCode());
            service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(next,future(15),"提前交付"));
            assertEquals(3L,jdbc.queryForObject("SELECT revision FROM task_reminder_plan WHERE task_id=?",Long.class,task));
            assertEquals(2,f.count("operation_log"));
        }
    }

    @Test
    void sentReminderRemainsHistoricalAfterRescheduling() {
        try (var f = ProjectFixture.create(jdbc)) {
            long task = f.task(f.developer, future(10)); state(task, "IN_PROGRESS");
            enabledReminders.planTask(task, LocalDateTime.now());
            long oldEvent = jdbc.queryForObject("SELECT id FROM task_reminder_event WHERE task_id=?", Long.class, task);
            enabledReminders.deliver(oldEvent);
            assertEquals(1, f.count("notification"));
            as(f.manager);
            service.adjustTaskDeadline(task, new AdjustTaskDeadlineDTO(deadline(task), future(20), "调整排期"));
            enabledReminders.deliver(oldEvent);
            assertEquals(1, f.count("notification"));
            assertEquals("DONE", jdbc.queryForObject("SELECT state FROM task_reminder_event WHERE id=?", String.class, oldEvent));
            enabledReminders.planTask(task, LocalDateTime.now());
            long current = jdbc.queryForObject("SELECT id FROM task_reminder_event WHERE task_id=? AND plan_revision=2", Long.class, task);
            enabledReminders.deliver(current);
            enabledReminders.deliver(current);
            assertEquals(2, f.count("notification"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"TODO","REVIEW","DONE","CANCELLED","PROJECT","MEMBER","PAST","SAME"})
    void forbiddenDeadlineChangesLeaveDataUntouched(String scenario) {
        try(var f=ProjectFixture.create(jdbc)) {
            long task=f.task(f.developer,future(10)); state(task,"IN_PROGRESS");
            if(java.util.Set.of("TODO","REVIEW","DONE","CANCELLED").contains(scenario)) state(task,scenario);
            if(scenario.equals("PROJECT")) jdbc.update("UPDATE project SET status='PENDING_ACCEPTANCE' WHERE id=?",f.projectId);
            as(scenario.equals("MEMBER") ? f.developer : f.manager);
            var old=deadline(task);
            var next=scenario.equals("PAST") ? LocalDateTime.now().minusDays(1) : scenario.equals("SAME") ? old : future(20);
            int expected=scenario.equals("MEMBER") ? 403 : java.util.Set.of("PAST","SAME").contains(scenario) ? 400 : 409;
            assertEquals(expected,assertThrows(BusinessException.class,()->service.adjustTaskDeadline(task,
                new AdjustTaskDeadlineDTO(old,next,"调整测试"))).getCode());
            assertEquals(old,deadline(task)); assertEquals(0,f.count("operation_log")); assertEquals(0,f.count("task_reminder_plan"));
        }
    }

    @Test
    void deadlineAuditAndPlanRollBackTogether() {
        try(var f=ProjectFixture.create(jdbc)) {
            long task=f.task(f.developer,future(10)); state(task,"IN_PROGRESS"); var old=deadline(task); as(f.manager);
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(old,future(20),"回滚测试"));
                assertEquals(1,f.count("task_reminder_plan")); assertEquals(1,f.count("operation_log"));
                throw new IllegalStateException("模拟提交前失败");
            }));
            assertEquals(old,deadline(task)); assertEquals(0,f.count("operation_log")); assertEquals(0,f.count("task_reminder_plan"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"deadline","submit","manager","cancelProject"})
    void deadlineWaitingForProjectLockRechecksCommittedState(String mutation) throws Exception {
        try(var f=ProjectFixture.create(jdbc)) {
            long task=f.task(f.developer,future(10)); state(task,"IN_PROGRESS"); var old=deadline(task); var changed=future(20);
            var pool=Executors.newFixedThreadPool(2); var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1);
            try {
                Future<?> writer=pool.submit(()->{
                    as(mutation.equals("submit") ? f.developer : f.manager);
                    try {new TransactionTemplate(transactions).executeWithoutResult(tx->{
                        assertNotNull(projects.selectByIdForUpdate(f.projectId));
                        if(mutation.equals("deadline")) service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(old,changed,"先完成的调整"));
                        else if (mutation.equals("manager")) {
                            jdbc.update("UPDATE project_member SET role='DEVELOPER' WHERE project_id=? AND user_id=?", f.projectId, f.manager);
                            jdbc.update("UPDATE project_member SET role='PROJECT_MANAGER' WHERE project_id=? AND user_id=?", f.projectId, f.target);
                        } else if (mutation.equals("cancelProject")) {
                            jdbc.update("UPDATE project SET status='CANCELLED' WHERE id=?", f.projectId);
                        } else {
                            var dto=new SubmitTaskDTO();dto.setCompletionNote("完成");dto.setTestNote("已验证");
                            service.submitTask(task,dto);
                        }
                        locked.countDown(); await(release);
                    });} finally {UserContext.remove();}
                });
                assertTrue(locked.await(10,TimeUnit.SECONDS));
                Future<?> reader=pool.submit(()->{as(f.manager);started.countDown();try{
                    service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(old,future(22),"等待中的旧请求"));
                }finally{UserContext.remove();}});
                assertTrue(started.await(10,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->reader.get(250,TimeUnit.MILLISECONDS));
                release.countDown();writer.get(15,TimeUnit.SECONDS);
                var error=assertThrows(ExecutionException.class,()->reader.get(15,TimeUnit.SECONDS));
                assertEquals(mutation.equals("manager") ? 403 : 409,assertInstanceOf(BusinessException.class,error.getCause()).getCode());
                assertEquals(mutation.equals("deadline") ? changed : old,deadline(task));
                assertEquals(mutation.equals("submit") ? "REVIEW" : "IN_PROGRESS",jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,task));
            } finally {release.countDown();pool.shutdown();if(!pool.awaitTermination(20,TimeUnit.SECONDS)){pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
        }
    }

    @Test
    void acceptanceRequiresAnActuallyCompletedTask() {
        try(var f=ProjectFixture.create(jdbc)) {
            as(f.manager);var dto=new SubmitProjectAcceptanceDTO();dto.setSubmissionNote("交付验收");dto.setRepositoryUrl("https://example.com/repo");
            assertEquals(409,assertThrows(BusinessException.class,()->acceptances.submitAcceptance(f.projectId,dto)).getCode());
            long task=f.task(f.developer,future(10));state(task,"CANCELLED");
            assertEquals(409,assertThrows(BusinessException.class,()->acceptances.submitAcceptance(f.projectId,dto)).getCode());
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM project_acceptance WHERE project_id=?",Long.class,f.projectId));
            state(task,"DONE");
            assertNotNull(acceptances.submitAcceptance(f.projectId,dto));
            assertEquals("PENDING_ACCEPTANCE",jdbc.queryForObject("SELECT status FROM project WHERE id=?",String.class,f.projectId));
        }
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("等待测试线程超时");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
}
