package com.flowdesk;

import com.flowdesk.context.CurrentUser;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.AdjustTaskDeadlineDTO;
import com.flowdesk.dto.ReviewTaskDTO;
import com.flowdesk.dto.SubmitTaskDTO;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.TaskSubmissionMapper;
import com.flowdesk.service.TaskQueryService;
import com.flowdesk.service.TaskService;
import com.flowdesk.support.IntegrationTest;
import com.flowdesk.support.ProjectFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@IntegrationTest
class TaskSubmissionTimingIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService service;
    @Autowired TaskQueryService queries;
    @Autowired TaskSubmissionMapper submissions;
    @Autowired ProjectMapper projects;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void clearUser() { UserContext.remove(); }
    void as(long user) { UserContext.set(new CurrentUser(user,"USER")); }
    SubmitTaskDTO payload() {
        var dto=new SubmitTaskDTO();dto.setCompletionNote("交付已完成");dto.setTestNote("验证通过");return dto;
    }
    void review(long task,String action) {
        var dto=new ReviewTaskDTO();dto.setAction(action);dto.setReviewNote("审核说明");service.reviewTask(task,dto);
    }
    long activeTask(ProjectFixture fixture,LocalDateTime deadline) {
        long id=fixture.task(fixture.developer,deadline);
        jdbc.update("UPDATE task SET status='IN_PROGRESS' WHERE id=?",id);return id;
    }
    void assertTiming(ProjectFixture fixture,long task,Boolean expected) {
        as(fixture.developer);
        assertEquals(expected,queries.getTaskDetail(task).getLateSubmission());
        assertEquals(expected,queries.getMyTasks(1,20,"ALL",null,"ALL").getRecords().stream()
            .filter(t->t.getId().equals(task)).findFirst().orElseThrow().getLateSubmission());
        assertEquals(expected,queries.getProjectTasks(fixture.projectId,"ALL","ALL",1,20,null,false).getRecords().stream()
            .filter(t->t.getId().equals(task)).findFirst().orElseThrow().getLateSubmission());
    }

    @Test void lateSubmissionRemainsLateAfterApprovalAndDoesNotUseReviewTime() {
        try(var f=ProjectFixture.create(jdbc)) {
            var deadline=LocalDateTime.now().minusHours(2).withNano(0);
            long task=activeTask(f,deadline);as(f.developer);
            long submitted=service.submitTask(task,payload());
            assertEquals(deadline,submissions.selectById(submitted).getDeadlineAtSubmission());
            assertTiming(f,task,true);
            assertTrue(queries.getTaskSubmissions(task).get(0).getLateSubmission());
            as(f.manager);review(task,"APPROVE");assertTiming(f,task,true);

            long onTime=activeTask(f,LocalDateTime.now().plusHours(1).withNano(0));
            as(f.developer);long second=service.submitTask(onTime,payload());
            as(f.manager);review(onTime,"APPROVE");
            jdbc.update("UPDATE task_submission SET reviewed_at=? WHERE id=?",LocalDateTime.now().plusDays(2),second);
            jdbc.update("UPDATE task SET completed_at=? WHERE id=?",LocalDateTime.now().plusDays(2),onTime);
            assertTiming(f,onTime,false);
        }
    }

    @Test void rejectionAndExtensionKeepEachSubmissionsOwnDeadline() {
        try(var f=ProjectFixture.create(jdbc)) {
            var old=LocalDateTime.now().minusHours(1).withNano(0);
            long task=activeTask(f,old);as(f.developer);
            service.submitTask(task,payload());as(f.manager);review(task,"REJECT");
            assertTiming(f,task,null);
            var next=LocalDateTime.now().plusDays(2).withNano(0);as(f.manager);
            service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(old,next,"延期整改"));
            assertEquals(f.developer,jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",Long.class,task));
            as(f.developer);service.submitTask(task,payload());
            var history=queries.getTaskSubmissions(task);
            assertEquals(2,history.size());assertEquals(old,history.get(0).getDeadlineAtSubmission());
            assertTrue(history.get(0).getLateSubmission());assertEquals(next,history.get(1).getDeadlineAtSubmission());
            assertFalse(history.get(1).getLateSubmission());
            as(f.manager);review(task,"APPROVE");assertTiming(f,task,false);
            assertTrue(queries.getTaskSubmissions(task).get(0).getLateSubmission());
        }
    }

    @Test void unknownSnapshotStaysUnknownAndDeadlineEqualityIsOnTime() {
        try(var f=ProjectFixture.create(jdbc)) {
            long task=f.task(f.developer,LocalDateTime.now().plusDays(1).withNano(0));
            var at=LocalDateTime.of(2026,1,1,12,0);
            long id=f.insert("INSERT INTO task_submission(task_id,submitter_id,submission_no,completion_note,test_note,review_status,submitted_at) VALUES (?, ?, 1, '完成', '通过', 'PENDING', ?)",task,f.developer,at);
            jdbc.update("UPDATE task SET status='REVIEW' WHERE id=?",task);
            assertTiming(f,task,null);assertNull(queries.getTaskSubmissions(task).get(0).getLateSubmission());
            jdbc.update("UPDATE task_submission SET deadline_at_submission=? WHERE id=?",at,id);
            assertTiming(f,task,false);assertFalse(queries.getTaskSubmissions(task).get(0).getLateSubmission());
            jdbc.update("UPDATE task_submission SET deadline_at_submission=? WHERE id=?",at.minusSeconds(1),id);
            assertTiming(f,task,true);
        }
    }

    @Test void submitWaitingForExtensionSnapshotsTheCommittedDeadline() throws Exception {
        try(var f=ProjectFixture.create(jdbc)) {
            var old=LocalDateTime.now().minusHours(1).withNano(0);
            var next=LocalDateTime.now().plusDays(1).withNano(0);
            long task=activeTask(f,old);
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
            var pool=Executors.newFixedThreadPool(2);
            try {
                Future<?> writer=pool.submit(()->{as(f.manager);try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx->{
                        projects.selectByIdForUpdate(f.projectId);
                        service.adjustTaskDeadline(task,new AdjustTaskDeadlineDTO(old,next,"并发延期"));
                        locked.countDown();await(release);
                    });
                }finally{UserContext.remove();}});
                assertTrue(locked.await(10,TimeUnit.SECONDS));
                Future<Long> submit=pool.submit(()->{as(f.developer);started.countDown();try{return service.submitTask(task,payload());}finally{UserContext.remove();}});
                assertTrue(started.await(10,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->submit.get(250,TimeUnit.MILLISECONDS));
                release.countDown();writer.get(15,TimeUnit.SECONDS);
                long id=submit.get(15,TimeUnit.SECONDS);
                assertEquals(next,submissions.selectById(id).getDeadlineAtSubmission());
                assertTiming(f,task,false);
            }finally{release.countDown();pool.shutdown();if(!pool.awaitTermination(20,TimeUnit.SECONDS)){pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
        }
    }
    static void await(CountDownLatch latch) {
        try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("等待超时");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
    }
}
