package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_call_log")
public class ModelCallLog {
    @TableId(type = IdType.AUTO) private Long id;
    private String invocationId;
    private String requestId;
    private Long userId;
    private String entryPoint;
    private String action;
    private String provider;
    private String model;
    private Integer attempt;
    private String status;
    /** Actual HTTP request JSON, not just the prompt. */
    private String inputText;
    /** Raw HTTP response, before extracting model content. */
    private String outputText;
    private String responseContent;
    private Integer httpStatus;
    private Integer tokenUsage;
    private String errorReason;
    private String errorType;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
}
