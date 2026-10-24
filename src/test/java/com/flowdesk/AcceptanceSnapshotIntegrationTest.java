package com.flowdesk;

import org.springframework.boot.test.context.TestConfiguration;

import com.flowdesk.support.IntegrationTest;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.flowdesk.config.MybatisPlusConfig;
import com.flowdesk.context.*;
import com.flowdesk.dto.*;
import com.flowdesk.enums.*;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.*;
import com.flowdesk.model.*;
import com.flowdesk.service.*;
import com.flowdesk.snapshot.AcceptanceSnapshotCodec;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 MySQL 验证映射器、JSON 列和 Spring 事务，不加载 Redis 上下文。 */
@SpringJUnitConfig(AcceptanceSnapshotIntegrationTest.DatabaseConfig.class)
@TestPropertySource("classpath:application-test.properties")
@IntegrationTest
class AcceptanceSnapshotIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class,DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,MybatisPlusAutoConfiguration.class})
    @MapperScan(basePackages="com.flowdesk.mapper",annotationClass=Mapper.class)
    @Import({MybatisPlusConfig.class,ProjectAcceptanceService.class,ProjectPermissionService.class,
            OperationLogService.class,NotificationService.class,ProjectRequestClosureService.class,ProjectService.class})
    static class DatabaseConfig {
        @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
        @Bean ControlledCodec codec(ObjectMapper mapper){return new ControlledCodec(mapper);}
    }
    static class ControlledCodec extends AcceptanceSnapshotCodec {
        volatile String fault;
        ControlledCodec(ObjectMapper mapper){super(mapper);}
        @Override public String encode(com.flowdesk.vo.ProjectAcceptanceDetailVO value){
            if("encode".equals(fault))throw new BusinessException(500,"Injected serialization failure");
            if("insert".equals(fault))return "invalid-json";
            return super.encode(value);
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired ProjectMapper projects;
    @Autowired ProjectMemberMapper members;
    @Autowired TaskMapper tasks;
    @Autowired ProjectAcceptanceMapper acceptances;
    @Autowired ProjectAcceptanceService service;
    @Autowired ProjectService projectService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ControlledCodec codec;
    final List<Long> userIds=new ArrayList<>();
    Long projectId;
    long pm,developer,admin;
    String projectName;

    @BeforeEach void seed(){
        assertEquals("flowdesk_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("flowdesk_test_user@"));
        codec.fault=null;
        pm=user("manager");developer=user("historical");admin=user("admin");
        jdbc.update("UPDATE `user` SET system_role='SYSTEM_ADMIN' WHERE id=?",admin);
        var project=new Project();projectName="snapshot-"+UUID.randomUUID();project.setName(projectName);
        project.setCreatorId(pm);project.setDescription("Initial description");project.setGoal("Initial goal");
        project.setStatus(ProjectStatus.IN_PROGRESS);project.setCreatedAt(LocalDateTime.now());project.setUpdatedAt(LocalDateTime.now());
        project.setStartTime(LocalDateTime.now());project.setExpectedEndTime(LocalDateTime.now().plusDays(30));
        projects.insert(project);projectId=project.getId();
        member(pm,"PROJECT_MANAGER",ProjectMemberStatus.ACTIVE);member(developer,"DEVELOPER",ProjectMemberStatus.INACTIVE);
        task(TaskStatus.DONE);
    }
    @AfterEach void cleanup(){
        codec.fault=null;UserContext.remove();
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            if(projectId!=null){
                jdbc.update("DELETE FROM task_comment WHERE task_id IN (SELECT id FROM task WHERE project_id=?)",projectId);
                jdbc.update("DELETE FROM task_submission WHERE task_id IN (SELECT id FROM task WHERE project_id=?)",projectId);
                for(String table:List.of("notification","operation_log","project_acceptance","project_invitation","project_manager_transfer","member_leave_request","task_request","task","project_member"))
                    jdbc.update("DELETE FROM `"+table+"` WHERE project_id=?",projectId);
                jdbc.update("DELETE FROM project WHERE id=?",projectId);
            }
            for(long id:userIds)assertEquals(1,jdbc.update("DELETE FROM `user` WHERE id=?",id));
        });
        if(projectId!=null){
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id=?",Long.class,projectId));
            for(String table:List.of("notification","operation_log","project_acceptance","project_invitation","project_manager_transfer","member_leave_request","task_request","task","project_member"))
                assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"` WHERE project_id=?",Long.class,projectId));
        }
        for(long id:userIds)assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=?",Long.class,id));
    }

    @Test void everyRoundRetainsItsOwnDeliveryAcrossReworkRoleChangesAndArchive(){
        as(pm,"USER");long first=service.submitAcceptance(projectId,submission("A"));
        String firstJson=stored(first);
        as(admin,"SYSTEM_ADMIN");var before=service.getAcceptanceDetail(first);
        assertEquals("SUBMISSION_SNAPSHOT",before.getDataSource());assertEquals(1,before.getCompletedTaskCount());
        assertEquals("historical",before.getMembers().stream().filter(m->m.getUserId()==developer).findFirst().orElseThrow().getUserName());
        review(first,"REJECT");
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            projects.selectByIdForUpdate(projectId);
            jdbc.update("UPDATE project SET name=?,description='Changed description' WHERE id=?",projectName+" changed",projectId);
            jdbc.update("UPDATE `user` SET real_name='Changed developer' WHERE id=?",developer);
            jdbc.update("UPDATE project_member SET status='ACTIVE',left_at=NULL WHERE project_id=? AND user_id=?",projectId,developer);
            assertEquals(1,members.demoteManagerToDeveloper(projectId,pm,LocalDateTime.now()));
            assertEquals(1,members.promoteDeveloperToManager(projectId,developer,LocalDateTime.now()));
            task(TaskStatus.DONE);
        });
        as(developer,"USER");long second=service.submitAcceptance(projectId,submission("B"));
        assertNotEquals(first,second);
        as(admin,"SYSTEM_ADMIN");var older=service.getAcceptanceDetail(first);var newer=service.getAcceptanceDetail(second);
        assertEquals("https://example.com/A",older.getRepositoryUrl());assertEquals("https://example.com/B",newer.getRepositoryUrl());
        assertEquals(projectName,older.getProjectName());assertEquals(projectName+" changed",newer.getProjectName());
        assertEquals(1,older.getCompletedTaskCount());assertEquals(2,newer.getCompletedTaskCount());
        assertEquals("DEVELOPER",older.getMembers().stream().filter(m->m.getUserId()==developer).findFirst().orElseThrow().getRole());
        assertEquals("Changed developer",newer.getSubmitterName());
        assertEquals("PROJECT_MANAGER",newer.getMembers().stream().filter(m->m.getUserId()==developer).findFirst().orElseThrow().getRole());
        review(second,"APPROVE");as(developer,"USER");projectService.archiveProject(projectId);
        as(admin,"SYSTEM_ADMIN");var archived=service.getAcceptanceDetail(first);
        assertEquals("ARCHIVED",archived.getProjectStatus());assertEquals("REJECTED",archived.getReviewStatus());
        assertEquals(firstJson,stored(first));assertEquals(2,archived.getAcceptanceHistory().size());
    }

    @Test void legacyPendingRecordIsLabeledLiveAndReviewDoesNotBackfillIt(){
        jdbc.update("UPDATE project SET status='PENDING_ACCEPTANCE' WHERE id=?",projectId);
        var old=new ProjectAcceptance();old.setProjectId(projectId);old.setSubmitterId(pm);old.setAcceptanceNo(1);
        old.setSubmissionNote("Legacy record");old.setReviewStatus(ProjectAcceptanceStatus.PENDING);old.setSubmittedAt(LocalDateTime.now());
        acceptances.insert(old);as(admin,"SYSTEM_ADMIN");
        var detail=service.getAcceptanceDetail(old.getId());assertEquals("LEGACY_CURRENT",detail.getDataSource());
        assertNull(detail.getSnapshotCapturedAt());assertNull(detail.getSnapshotVersion());assertEquals(1,detail.getCompletedTaskCount());
        review(old.getId(),"APPROVE");var saved=acceptances.selectById(old.getId());
        assertNull(saved.getSnapshotVersion());assertNull(saved.getSnapshotJson());assertEquals(ProjectAcceptanceStatus.APPROVED,saved.getReviewStatus());
    }

    @ParameterizedTest @ValueSource(strings={"encode","insert"})
    void snapshotFailuresLeaveNeitherPendingProjectNorPartialAcceptance(String fault){
        codec.fault=fault;as(pm,"USER");assertThrows(RuntimeException.class,()->service.submitAcceptance(projectId,submission("A")));
        assertEquals("IN_PROGRESS",jdbc.queryForObject("SELECT status FROM project WHERE id=?",String.class,projectId));
        for(String table:List.of("project_acceptance","operation_log","notification"))
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"` WHERE project_id=?",Long.class,projectId));
    }

    @ParameterizedTest @ValueSource(strings={"DONE","TODO"})
    void snapshotWaitsForProjectWriterAndUsesCommittedData(String status)throws Exception{
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);final long pid=projectId;
        try{
            Future<?> writer=pool.submit(()->{
                var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                tx.executeWithoutResult(txStatus->{projects.selectByIdForUpdate(pid);task(TaskStatus.valueOf(status));
                    jdbc.update("UPDATE `user` SET real_name='Committed member' WHERE id=?",developer);locked.countDown();
                    try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Writer release timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}});
            });
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<Long> submission=pool.submit(()->{as(pm,"USER");started.countDown();try{return service.submitAcceptance(pid,submission("A"));}finally{UserContext.remove();}});
            assertTrue(started.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->submission.get(300,TimeUnit.MILLISECONDS));
            release.countDown();writer.get(15,TimeUnit.SECONDS);
            if(status.equals("TODO")){
                var failure=assertThrows(ExecutionException.class,()->submission.get(15,TimeUnit.SECONDS));
                assertEquals(409,assertInstanceOf(BusinessException.class,failure.getCause()).getCode());
                assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM project_acceptance WHERE project_id=?",Long.class,pid));
            }else{
                long id=submission.get(15,TimeUnit.SECONDS);as(admin,"SYSTEM_ADMIN");var detail=service.getAcceptanceDetail(id);
                assertEquals(2,detail.getCompletedTaskCount());
                assertEquals("Committed member",detail.getMembers().stream().filter(m->m.getUserId()==developer).findFirst().orElseThrow().getUserName());
            }
        }finally{release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(35,TimeUnit.SECONDS));}
    }

    long user(String label){var u=new User();u.setUsername("snap_"+UUID.randomUUID().toString().substring(0,8)+"_"+label);
        u.setPasswordHash("integration-test-only");u.setRealName(label);u.setSystemRole("USER");u.setStatus(UserStatus.ACTIVE);
        u.setCreatedAt(LocalDateTime.now());u.setUpdatedAt(LocalDateTime.now());users.insert(u);userIds.add(u.getId());return u.getId();}
    void member(long id,String role,ProjectMemberStatus status){var m=new ProjectMember();m.setProjectId(projectId);m.setUserId(id);m.setRole(role);m.setStatus(status);m.setJoinedAt(LocalDateTime.now());m.setUpdatedAt(LocalDateTime.now());if(status==ProjectMemberStatus.INACTIVE)m.setLeftAt(LocalDateTime.now());members.insert(m);}
    void task(TaskStatus status){var t=new Task();t.setProjectId(projectId);t.setCreatorId(pm);t.setAssigneeId(developer);t.setTitle("Snapshot task "+UUID.randomUUID());t.setDescription("Fixture");t.setGoal("Verify snapshots");t.setStatus(status);t.setPriority("MEDIUM");t.setDeadline(LocalDateTime.now().plusDays(5));t.setCreatedAt(LocalDateTime.now());t.setUpdatedAt(LocalDateTime.now());if(status==TaskStatus.DONE)t.setCompletedAt(LocalDateTime.now());tasks.insert(t);}
    SubmitProjectAcceptanceDTO submission(String suffix){var d=new SubmitProjectAcceptanceDTO();d.setSubmissionNote("Snapshot round "+suffix);d.setRepositoryUrl("https://example.com/"+suffix);return d;}
    void review(long id,String action){var d=new ReviewProjectAcceptanceDTO();d.setAction(action);d.setReviewNote("Snapshot integration review");service.reviewAcceptance(id,d);}
    void as(long id,String role){UserContext.set(new CurrentUser(id,role));}
    String stored(long id){return jdbc.queryForObject("SELECT snapshot_json FROM project_acceptance WHERE id=?",String.class,id);}
}
