package com.dealmanager.ai;

import com.dealmanager.domain.UserAiSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class AiPreferencesTest {
    @Test
    void emptySettingsHaveTheSameDefaultsAsUnifiedSettingsPage() {
        var preferences = AiPreferences.from(null);
        assertEquals("自然亲切", preferences.replyStyle());
        assertEquals("自动判断", preferences.preferredAddress());
        assertEquals("适中", preferences.replyLength());
        assertEquals("", preferences.businessIntro());
        assertEquals(preferences.fingerprint(), AiPreferences.from(new UserAiSetting()).fingerprint());
    }

    @Test
    void fingerprintTracksAllSixPreferencesButIgnoresStorageMetadata() throws Exception {
        UserAiSetting setting = new UserAiSetting();
        String[] hashes = new String[7];
        hashes[0] = AiPreferences.from(setting).fingerprint();
        setting.setReplyStyle("专业正式"); hashes[1] = AiPreferences.from(setting).fingerprint();
        setting.setPreferredAddress("您"); hashes[2] = AiPreferences.from(setting).fingerprint();
        setting.setReplyLength("详细"); hashes[3] = AiPreferences.from(setting).fingerprint();
        setting.setBusinessIntro("住宅装修"); hashes[4] = AiPreferences.from(setting).fingerprint();
        setting.setBusinessAdvantage("报价透明"); hashes[5] = AiPreferences.from(setting).fingerprint();
        setting.setCommonExpression("为您确认"); hashes[6] = AiPreferences.from(setting).fingerprint();
        for (int i = 1; i < hashes.length; i++) assertNotEquals(hashes[i - 1], hashes[i]);
        setting.setId(100L); setting.setUserId(7L); setting.setUpdatedAt(LocalDateTime.now());
        assertEquals(hashes[6], AiPreferences.from(setting).fingerprint());
        var payload = new ObjectMapper().readTree(AiPreferences.from(setting).toJson());
        assertEquals(6, payload.size()); assertFalse(payload.has("userId"));
    }
}
