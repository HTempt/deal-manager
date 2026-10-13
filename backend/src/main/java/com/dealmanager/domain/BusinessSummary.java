package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("business_summary")
public class BusinessSummary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String summaryMonth;
    private String content;
    private String prompt;
    private String model;
    private Integer tokenUsage;
    private String aiSettingHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
