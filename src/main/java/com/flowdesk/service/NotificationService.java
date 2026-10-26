package com.flowdesk.service;

import com.flowdesk.enums.UserStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flowdesk.context.UserContext;
import com.flowdesk.common.ListPagination;
import com.flowdesk.common.PageResult;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.NotificationMapper;
import com.flowdesk.mapper.UserMapper;
import com.flowdesk.model.Notification;
import com.flowdesk.model.User;
import com.flowdesk.vo.NotificationVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;

    public NotificationService(NotificationMapper notificationMapper, UserMapper userMapper) {
        this.notificationMapper = notificationMapper;
        this.userMapper = userMapper;
    }

    public void createNotification(CreateNotificationDTO dto) {
        User recipient = userMapper.selectById(dto.getRecipientId());

        if (recipient == null || !UserStatus.ACTIVE.equals(recipient.getStatus())) {
            return;
        }

        Notification notification = new Notification();

        notification.setRecipientId(dto.getRecipientId());
        notification.setActorId(dto.getActorId());

        notification.setType(dto.getType());
        notification.setTitle(dto.getTitle());
        notification.setContent(dto.getContent());

        notification.setProjectId(dto.getProjectId());

        notification.setTargetType(dto.getTargetType());
        notification.setTargetId(dto.getTargetId());

        notification.setCreatedAt(LocalDateTime.now());

        notificationMapper.insert(notification);
    }

    public PageResult<NotificationVO> getMyNotifications(long page, long size, String readFilter, String category) {
        Long userId = UserContext.get().getUserId();
        var notificationPage = ListPagination.<Notification>page(page, size);
        String normalizedRead = readFilter.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedCategory = category.trim().toUpperCase(java.util.Locale.ROOT);
        LambdaQueryWrapper<Notification> query = new LambdaQueryWrapper<Notification>().eq(Notification::getRecipientId, userId);
        switch (normalizedRead) {
            case "ALL" -> {
            }
            case "UNREAD" -> query.isNull(Notification::getReadAt);
            case "READ" -> query.isNotNull(Notification::getReadAt);
            default -> throw new BusinessException(400, "readFilter 只能是 ALL、UNREAD 或 READ");
        }
        // 与页面原有字面量 startsWith / === 匹配一致，避免 LIKE 把下划线当通配符。
        switch (normalizedCategory) {
            case "ALL" -> {
            }
            case "TASK" -> query.apply("CAST(type AS BINARY) IN (CAST({0} AS BINARY), CAST({1} AS BINARY), CAST({2} AS BINARY))",
                "TASK_ASSIGNED", "TASK_DUE_SOON", "TASK_OVERDUE");
            case "PROJECT_INVITATION", "TASK_REQUEST", "TASK_SUBMISSION", "PROJECT_ACCEPTANCE" -> query.apply(
                "CAST(LEFT(type, {0}) AS BINARY) = CAST({1} AS BINARY)",
                normalizedCategory.length(),
                normalizedCategory
            );
            case "MEMBER_LEAVE_REQUEST" -> query.apply(
                "CAST(LEFT(type, {0}) AS BINARY) = CAST({1} AS BINARY)",
                "MEMBER_LEAVE_REQUEST_".length(),
                "MEMBER_LEAVE_REQUEST_"
            );
            case "PROJECT_MANAGER_TRANSFER" -> query.and(group ->
                group
                    .apply("CAST(type AS BINARY) = CAST({0} AS BINARY)", "PROJECT_MANAGER_TRANSFER")
                    .or()
                    .apply(
                        "CAST(LEFT(type, {0}) AS BINARY) = CAST({1} AS BINARY)",
                        "PROJECT_MANAGER_TRANSFER_".length(),
                        "PROJECT_MANAGER_TRANSFER_"
                    )
            );
            case "PROJECT_MEMBER_REMOVED", "PROJECT_MEMBER_DISABLED" -> query.apply(
                "CAST(type AS BINARY) = CAST({0} AS BINARY)",
                normalizedCategory
            );
            default -> throw new BusinessException(400, "通知类别筛选条件不正确");
        }
        query.orderByDesc(Notification::getCreatedAt).orderByDesc(Notification::getId);
        var result = notificationMapper.selectPage(notificationPage, query);
        return new PageResult<>(
            result.getRecords().stream().map(this::toVO).toList(),
            result.getTotal(),
            result.getCurrent(),
            result.getSize(),
            result.getPages()
        );
    }

    public Long getUnreadCount() {
        Long userId = UserContext.get().getUserId();

        return notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>().eq(Notification::getRecipientId, userId).isNull(Notification::getReadAt)
        );
    }

    public void markAsRead(Long notificationId) {
        Notification notification = notificationMapper.selectById(notificationId);

        if (notification == null) {
            throw new BusinessException(404, "通知不存在");
        }

        Long userId = UserContext.get().getUserId();

        if (!userId.equals(notification.getRecipientId())) {
            throw new BusinessException(403, "无权操作该通知");
        }

        if (notification.getReadAt() != null) {
            return;
        }

        notification.setReadAt(LocalDateTime.now());

        notificationMapper.updateById(notification);
    }

    public void markAllAsRead() {
        Long userId = UserContext.get().getUserId();

        notificationMapper.update(
            null,
            new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientId, userId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now())
        );
    }

    private NotificationVO toVO(Notification notification) {
        NotificationVO vo = new NotificationVO();

        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setProjectId(notification.getProjectId());
        vo.setTargetType(notification.getTargetType());
        vo.setTargetId(notification.getTargetId());
        vo.setReadAt(notification.getReadAt());
        vo.setCreatedAt(notification.getCreatedAt());

        return vo;
    }
}
