package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("customer_summary")
public class CustomerSummary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long customerId;
    private String overview;
    private String suggestion;
    private String sourceHash;
    private Integer recordsUsed;
    private String prompt;
    private String model;
    private Integer tokenUsage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
