package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("follow_record")
public class FollowRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private Long userId;
    private String followType;
    private String content;
    private String customerFeedback;
    private Boolean activeInquiry;
    private Boolean priceInquiry;
    private Integer intentionChange;
    private Integer intentionBefore;
    private Integer intentionAfter;
    private String intentionMode;
    private LocalDateTime nextFollowTime;
    private LocalDateTime createdAt;
}
