package cn.watsontech.snapagent.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserInfoTest {

    @Test
    void shouldGetAndSetLlmBridgeEnabled() {
        UserInfo info = new UserInfo();
        
        // Default
        assertThat(info.isLlmBridgeEnabled()).isFalse();
        
        // Set to true
        info.setLlmBridgeEnabled(true);
        assertThat(info.isLlmBridgeEnabled()).isTrue();
        
        // Set to false
        info.setLlmBridgeEnabled(false);
        assertThat(info.isLlmBridgeEnabled()).isFalse();
    }

    @Test
    void shouldGetAndSetBridgeEnabled() {
        UserInfo info = new UserInfo();
        
        assertThat(info.isBridgeEnabled()).isFalse();
        
        info.setBridgeEnabled(true);
        assertThat(info.isBridgeEnabled()).isTrue();
    }
}
