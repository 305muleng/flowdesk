package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.dto.SubmitProjectAcceptanceDTO;
import com.flowdesk.service.ProjectAcceptanceService;
import com.flowdesk.vo.ProjectAcceptanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "项目验收",
        description = "项目负责人提交项目验收以及查看相关验收信息"
)
@RestController
@RequestMapping("/projects/{projectId}/acceptances")
public class ProjectAcceptanceController {

    private final ProjectAcceptanceService projectAcceptanceService;

    public ProjectAcceptanceController(ProjectAcceptanceService projectAcceptanceService) {
        this.projectAcceptanceService = projectAcceptanceService;
    }

    @Operation(
            summary = "提交项目验收",
            description = "项目负责人在项目满足验收条件后提交验收申请，并将项目状态从 IN_PROGRESS 变为 PENDING_ACCEPTANCE"
    )
    @PostMapping
    public Result<Long> submitAcceptance(@PathVariable Long projectId, @Valid @RequestBody SubmitProjectAcceptanceDTO dto) {
        Long acceptanceId = projectAcceptanceService.submitAcceptance(projectId, dto);
        return Result.success("项目验收已提交", acceptanceId);
    }

    @Operation(summary = "查看项目验收记录", description = "项目成员查看指定项目的历次验收记录")
    @GetMapping
    public Result<List<ProjectAcceptanceVO>> getProjectAcceptances(@PathVariable Long projectId) {
        return Result.success(projectAcceptanceService.getProjectAcceptances(projectId));
    }
}
