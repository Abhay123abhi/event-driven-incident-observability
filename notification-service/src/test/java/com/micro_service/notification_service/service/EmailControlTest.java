package com.micro_service.notification_service.service;

import com.micro_service.notification_service.config.NotificationProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmailControlTest {
    private NotificationProperties properties(boolean enabled) {
        return new NotificationProperties(enabled, "sender@example.invalid", "recipient@example.invalid");
    }
    @Test
    void switchChangesImmediatelyAndRestartUsesEnvironmentDefault() {
        var control = new EmailControl(properties(false), "smtp.local", "user", "password", true);
        assertThat(control.enabled()).isFalse();
        assertThat(control.update(true).enabled()).isTrue();
        assertThat(control.update(false).enabled()).isFalse();
        assertThat(new EmailControl(properties(false), "smtp.local", "user", "password", true).enabled()).isFalse();
    }
    @Test
    void missingCredentialsCannotBeEnabledAndCanAlwaysBeMuted() {
        var control = new EmailControl(properties(true), "smtp.local", "", "", true);
        assertThat(control.enabled()).isFalse();
        assertThat(control.state().configured()).isFalse();
        assertThatThrownBy(() -> control.update(true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(control.update(false).enabled()).isFalse();
    }
    @Test
    void localSmtpWithoutAuthenticationDoesNotRequireCredentials() {
        assertThat(new EmailControl(properties(false), "mailpit", "", "", false)
                .update(true).enabled()).isTrue();
    }
}
