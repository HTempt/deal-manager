package com.dealmanager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dealmanager.common.ApiException;
import com.dealmanager.domain.IndustryConfig;
import com.dealmanager.domain.User;
import com.dealmanager.domain.UserAiSetting;
import com.dealmanager.mapper.IndustryConfigMapper;
import com.dealmanager.mapper.UserAiSettingMapper;
import com.dealmanager.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {
    private final UserMapper userMapper;
    private final UserAiSettingMapper aiSettingMapper;
    private final IndustryConfigMapper industryMapper;

    public User user(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw ApiException.notFound("用户不存在");
        return user;
    }

    public User updateProfile(Long userId, String nickname, String phone) {
        User user = user(userId);
        if (nickname != null) user.setNickname(nickname);
        if (phone != null) user.setPhone(phone);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("settings.profile_updated userId={}", userId);
        return user;
    }

    public List<IndustryConfig> industries() {
        return industryMapper.selectList(new LambdaQueryWrapper<IndustryConfig>().orderByAsc(IndustryConfig::getId));
    }

    public User setIndustry(Long userId, String industry) {
        User user = user(userId);
        user.setIndustry(industry);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("settings.industry_updated userId={}", userId);
        return user;
    }

    public UserAiSetting aiSetting(Long userId) {
        UserAiSetting setting = aiSettingMapper.selectOne(new LambdaQueryWrapper<UserAiSetting>().eq(UserAiSetting::getUserId, userId));
        if (setting == null) {
            setting = new UserAiSetting();
            setting.setUserId(userId);
            setting.setReplyStyle("自然亲切");
            setting.setReplyLength("适中");
            setting.setPreferredAddress("自动判断");
            setting.setCreatedAt(LocalDateTime.now());
            setting.setUpdatedAt(LocalDateTime.now());
            aiSettingMapper.insert(setting);
        }
        return setting;
    }

    @Transactional
    public UserAiSetting updateAi(Long userId, AiSettingCommand command) {
        UserAiSetting setting = aiSetting(userId);
        setting.setReplyStyle(command.replyStyle());
        setting.setReplyLength(command.replyLength());
        setting.setPreferredAddress(command.preferredAddress());
        setting.setBusinessIntro(command.businessIntro());
        setting.setBusinessAdvantage(command.businessAdvantage());
        setting.setCommonExpression(command.commonExpression());
        setting.setUpdatedAt(LocalDateTime.now());
        aiSettingMapper.updateById(setting);
        log.info("settings.ai_updated userId={}", userId);
        return setting;
    }

    public record AiSettingCommand(String replyStyle, String replyLength, String preferredAddress,
                                   String businessIntro, String businessAdvantage, String commonExpression) {}
}
