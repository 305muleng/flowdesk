package com.flowdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flowdesk.context.UserContext;
import com.flowdesk.dto.CreateNotificationDTO;
import com.flowdesk.exception.BusinessException;
import com.flowdesk.mapper.NotificationMapper;
import com.flowdesk.model.Notification;
import com.flowdesk.vo.NotificationVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public void createNotification(CreateNotificationDTO dto) {

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

    public List<NotificationVO> getMyNotifications() {

        Long userId = UserContext.get().getUserId();

        List<Notification> notifications = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getRecipientId, userId)
                        .orderByDesc(Notification::getCreatedAt)
                        .orderByDesc(Notification::getId)
        );

        return notifications.stream()
                .map(this::toVO)
                .toList();
    }

    public Long getUnreadCount() {

        Long userId = UserContext.get().getUserId();

        return notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getRecipientId, userId)
                        .isNull(Notification::getReadAt)
        );
    }

    public void markAsRead(Long notificationId) {

        Notification notification =
                notificationMapper.selectById(notificationId);

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