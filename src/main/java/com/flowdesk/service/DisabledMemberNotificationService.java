package com.flowdesk.service;

import com.flowdesk.enums.ProjectStatus;
import com.flowdesk.enums.ProjectMemberStatus;

import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.event.UserDisabledEvent;
import com.flowdesk.mapper.ProjectMapper;
import com.flowdesk.mapper.ProjectMemberMapper;
import com.flowdesk.mapper.TaskMapper;
import com.flowdesk.mapper.TaskQueryConditions;
import com.flowdesk.model.Project;
import com.flowdesk.vo.ProjectMemberVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

@Service
public class DisabledMemberNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DisabledMemberNotificationService.class);
    private static final Set<ProjectStatus> ACTIONABLE_STATUSES = Set.of(
        ProjectStatus.PREPARING,
        ProjectStatus.IN_PROGRESS,
        ProjectStatus.PENDING_ACCEPTANCE
    );

    private final ProjectMemberMapper members;
    private final ProjectMapper projects;
    private final TaskMapper tasks;
    private final NotificationService notifications;
    private final TransactionTemplate independentTransaction;

    public DisabledMemberNotificationService(
        ProjectMemberMapper members,
        ProjectMapper projects,
        TaskMapper tasks,
        NotificationService notifications,
        PlatformTransactionManager transactionManager
    ) {
        this.members = members;
        this.projects = projects;
        this.tasks = tasks;
        this.notifications = notifications;
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 默认 AFTER_COMMIT：禁用已经提交；每个项目使用新事务，失败不会污染禁用事务或其他项目。
    @TransactionalEventListener
    public void onUserDisabled(UserDisabledEvent event) {
        List<Long> projectIds;
        try {
            projectIds = independentTransaction.execute(status -> members.selectNotificationProjectIds(event.userId()));
        } catch (RuntimeException e) {
            log.warn("查询禁用成员相关项目失败，userId={}", event.userId(), e);
            return;
        }
        for (Long projectId : projectIds) {
            try {
                independentTransaction.executeWithoutResult(status -> notifyProject(projectId, event));
            } catch (RuntimeException e) {
                log.warn("禁用成员通知失败，projectId={}, userId={}", projectId, event.userId(), e);
            }
        }
    }

    private void notifyProject(Long projectId, UserDisabledEvent event) {
        Project project = projects.selectByIdForUpdate(projectId);
        if (project == null || project.getDeletedAt() != null || !ACTIONABLE_STATUSES.contains(project.getStatus())) {
            return;
        }
        var membership = members.selectMemberForUpdate(projectId, event.userId());
        if (membership == null || !ProjectMemberStatus.ACTIVE.equals(membership.getStatus())) {
            return;
        }
        ProjectMemberVO manager = members.selectActiveProjectManager(projectId);
        if (manager == null) {
            throw new IllegalStateException("相关项目缺少有效项目负责人");
        }
        long count = tasks.selectCount(
            TaskQueryConditions.unfinishedForAssignee(projectId, event.userId())
        );

        CreateNotificationDTO dto = new CreateNotificationDTO();
        dto.setRecipientId(manager.getUserId());
        dto.setActorId(event.actorId());
        dto.setProjectId(projectId);
        dto.setType("PROJECT_MEMBER_DISABLED");
        dto.setTitle("项目成员账号已禁用");
        dto.setContent(
            "项目“" +
                project.getName() +
                "”成员“" +
                event.userName() +
                "”账号已禁用。" +
                (count == 0 ? "无待交接任务。" : "尚有 " + count + " 项未完成任务，请查看成员任务。") +
                (ProjectStatus.PENDING_ACCEPTANCE.equals(project.getStatus()) ? "项目待验收期间继续冻结，不能修改任务。" : "")
        );
        dto.setTargetType("PROJECT");
        dto.setTargetId(projectId);
        notifications.createNotification(dto);
    }
}
