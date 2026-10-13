package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("deal")
public class Deal {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long customerId;
    private BigDecimal amount;
    private String productName;
    private LocalDateTime dealTime;
    private String source;
    private LocalDateTime createdAt;
}
