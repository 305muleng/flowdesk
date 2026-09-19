package com.flowdesk.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.OperationLogMapper;
import com.flowdesk.model.OperationLog;
import com.flowdesk.vo.OperationLogVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;
    private final ProjectPermissionService projectPermissionService;

    public OperationLogService(
            OperationLogMapper operationLogMapper,
            ObjectMapper objectMapper,
            ProjectPermissionService projectPermissionService) {

        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
        this.projectPermissionService = projectPermissionService;
    }

    public List<OperationLogVO> getProjectLogs(Long projectId, int limit) {
        projectPermissionService.requireProjectMember(projectId);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return operationLogMapper.selectProjectLogs(projectId, safeLimit);
    }

    public void record(
            Long projectId,
            Long actorId,
            String targetType,
            Long targetId,
            String action,
            String description,
            Object beforeData,
            Object afterData) {

        OperationLog log = new OperationLog();

        log.setProjectId(projectId);
        log.setActorId(actorId);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setAction(action);
        log.setDescription(description);
        log.setCreatedAt(LocalDateTime.now());

        try {

            if (beforeData != null) {
                log.setBeforeData(
                        objectMapper.writeValueAsString(beforeData)
                );
            }

            if (afterData != null) {
                log.setAfterData(
                        objectMapper.writeValueAsString(afterData)
                );
            }

        } catch (JacksonException e) {

            throw new BusinessException(
                    500,
                    "操作日志序列化失败"
            );
        }

        operationLogMapper.insert(log);
    }
}
