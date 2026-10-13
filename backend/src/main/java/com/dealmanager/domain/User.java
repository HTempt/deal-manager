package com.dealmanager.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("`user`")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String openid;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private String industry;
    private String membershipType;
    private LocalDateTime membershipExpireTime;
    private Boolean notificationEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
