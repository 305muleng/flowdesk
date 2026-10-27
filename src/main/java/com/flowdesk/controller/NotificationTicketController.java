package com.flowdesk.controller;

import com.flowdesk.common.Result;
import com.flowdesk.realtime.NotificationTicketService;
import com.flowdesk.vo.NotificationTicketVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "通知")
@RestController
@RequestMapping("/notifications")
public class NotificationTicketController {
    private final NotificationTicketService tickets;
    public NotificationTicketController(NotificationTicketService tickets) { this.tickets = tickets; }

    @Operation(summary = "申请实时通知连接票据")
    @PostMapping("/ws-ticket")
    public Result<NotificationTicketVO> issue(@RequestHeader("Authorization") String authorization) {
        return Result.success(tickets.issue(authorization));
    }
}
