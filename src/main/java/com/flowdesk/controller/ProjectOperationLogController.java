package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.service.OperationLogService;
import com.flowdesk.vo.OperationLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "项目操作日志", description = "项目成员查看项目关键业务操作记录")
@RestController
@RequestMapping("/projects/{projectId}/operation-logs")
public class ProjectOperationLogController {

    private final OperationLogService operationLogService;

    public ProjectOperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Operation(summary = "查看项目操作日志")
    @GetMapping
    public Result<List<OperationLogVO>> getProjectLogs(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(operationLogService.getProjectLogs(projectId, limit));
    }
}
