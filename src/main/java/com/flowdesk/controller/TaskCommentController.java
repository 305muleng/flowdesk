package com.flowdesk.controller;

import com.flowdesk.common.*;
import com.flowdesk.dto.*;
import com.flowdesk.service.*;
import com.flowdesk.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "任务管理")
@RestController
public class TaskCommentController {
    private final TaskCommentService taskCommentService;
    public TaskCommentController(TaskCommentService taskCommentService) { this.taskCommentService = taskCommentService; }

    @Operation(summary = "新增任务评论", description = "项目成员对任务发表评论，也可以通过 parentId 回复已有评论")
    @PostMapping("/tasks/{taskId}/comments")
    public Result<Long> addTaskComment(@PathVariable Long taskId, @Valid @RequestBody AddTaskCommentDTO dto) {
        Long commentId = taskCommentService.addTaskComment(taskId, dto);
        return Result.success("评论发表成功", commentId);
    }
}
