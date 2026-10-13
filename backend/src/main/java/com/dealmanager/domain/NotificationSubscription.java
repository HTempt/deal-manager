package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_subscription")
public class NotificationSubscription {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private String templateId;
    private String subscribeToken;
    private LocalDateTime expiresAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
