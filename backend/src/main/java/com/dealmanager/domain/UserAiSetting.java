package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_ai_setting")
public class UserAiSetting {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String replyStyle;
    private String replyLength;
    private String preferredAddress;
    private String businessIntro;
    private String businessAdvantage;
    private String commonExpression;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
