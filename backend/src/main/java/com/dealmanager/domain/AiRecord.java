package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_record")
public class AiRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long customerId;
    private String scenario;
    private String prompt;
    private String response;
    private String model;
    private Integer tokenUsage;
    private LocalDateTime createdAt;
}
