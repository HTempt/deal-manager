package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("notification_send_log")
public class NotificationSendLog {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private Long taskId;
    private String notificationType;
    private LocalDate summaryDate;
    private String templateId;
    private String status;
    private String message;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
}
