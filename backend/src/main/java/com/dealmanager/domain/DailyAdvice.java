package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每个用户每天一份 AI 今日建议，按北京时间日期保存，重新生成时覆盖当天结果。 */
@Data
@TableName("daily_advice")
public class DailyAdvice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate adviceDate;
    private String overview;
    private String items;
    private Integer candidateCount;
    private String sourceHash;
    private String prompt;
    private String model;
    private Integer tokenUsage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}