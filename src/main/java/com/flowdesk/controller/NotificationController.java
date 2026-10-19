package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.common.PageResult;
import com.flowdesk.service.NotificationService;
import com.flowdesk.vo.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "通知", description = "当前用户通知查询与已读状态管理")
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "查询我的通知")
    @GetMapping
    public Result<PageResult<NotificationVO>> getMyNotifications(
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "20") long size,
        @RequestParam(defaultValue = "ALL") String readFilter,
        @RequestParam(defaultValue = "ALL") String category
    ) {
        return Result.success(notificationService.getMyNotifications(page, size, readFilter, category));
    }

    @Operation(summary = "查询未读通知数量")
    @GetMapping("/unread-count")
    public Result<Long> getUnreadCount() {
        return Result.success(notificationService.getUnreadCount());
    }

    @Operation(summary = "标记通知为已读")
    @PutMapping("/{notificationId}/read")
    public Result<Void> markAsRead(@PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId);
        return Result.success(null);
    }

    @Operation(summary = "全部标记为已读")
    @PutMapping("/read-all")
    public Result<Void> markAllAsRead() {
        notificationService.markAllAsRead();
        return Result.success(null);
    }
}
