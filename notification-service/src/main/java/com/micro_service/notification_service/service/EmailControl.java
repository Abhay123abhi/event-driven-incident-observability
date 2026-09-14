package com.micro_service.notification_service.service;

import com.micro_service.notification_service.config.NotificationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class EmailControl {
    private final AtomicBoolean enabled;
    private final boolean configured;
    private final boolean startupEnabled;

    public EmailControl(NotificationProperties properties,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean auth) {
        configured = !host.isBlank() && address(properties.from()) && address(properties.recipient())
                && (!auth || (!username.isBlank() && !password.isBlank()));
        startupEnabled = properties.enabled();
        enabled = new AtomicBoolean(startupEnabled && configured);
    }

    private static boolean address(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            var address = new jakarta.mail.internet.InternetAddress(value, true);
            address.validate();
            return true;
        } catch (jakarta.mail.internet.AddressException e) { return false; }
    }

    public boolean enabled() { return enabled.get(); }
    public State state() { return new State(enabled(), configured, startupEnabled); }
    public State update(boolean value) {
        if (value && !configured)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure SMTP and email addresses in .env first");
        enabled.set(value);
        return state();
    }
    public record State(boolean enabled, boolean configured, boolean startupEnabled) {}
}
