package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "customer", autoResultMap = true)
public class Customer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String city;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String address;
    private String phone;
    @TableField("need_text")
    private String need;
    private BigDecimal budget;
    private String source;
    @TableField(value = "tags", typeHandler = JacksonTypeHandler.class)
    private List<String> tags;
    private String stage;
    private Integer funnelStageRank;
    private Integer intentionScore;
    private String intentionMode;
    private Integer followCount;
    private LocalDateTime lastFollowTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextFollowTime;
    private BigDecimal dealAmount;
    private LocalDateTime dealTime;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private Long daysWithoutFollow;
    @TableField(exist = false)
    private String dormancyLevel;
    @TableField(exist = false)
    private Boolean hasContactHistory;
}
