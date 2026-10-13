package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("industry_config")
public class IndustryConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String industryCode;
    private String industryName;
    private String stages;
    private String aiScenarios;
    private LocalDateTime createdAt;
}
