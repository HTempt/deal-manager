package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("follow_task")
public class FollowTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long customerId;
    private String taskType;
    private String title;
    private LocalDateTime planTime;
    private LocalDateTime completedTime;
    private String status;
    private LocalDateTime createdAt;
}
