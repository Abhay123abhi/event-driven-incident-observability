package com.micro_service.notification_service.service;

import com.micro_service.notification_service.api.IncidentEvent;
import com.micro_service.notification_service.config.NotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.thymeleaf.TemplateEngine;

import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final TemplateEngine templates = mock(TemplateEngine.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private IncidentEvent event() {
        return new IncidentEvent("id", "fingerprint", "INVESTIGATED", "inventory-service",
                "HighResponseLatency", "Inventory is slow", "WARNING", List.of("p95_latency_seconds=3"),
                List.of("inventory-service"), List.of("Inspect traces"), "http://localhost:3000", Instant.now());
    }

    private NotificationService service(boolean enabled) {
        return new NotificationService(mail, templates,
                new NotificationProperties(enabled, "sender@example.invalid", "recipient@example.invalid"), meters,
                new EmailControl(new NotificationProperties(enabled, "sender@example.invalid", "recipient@example.invalid"),
                        "smtp.local", "user", "password", true));
    }

    @Test
    void mutedConsumerAcceptsRepeatedEventsWithoutRenderingOrSendingMail() {
        var service = service(false);
        for (int i = 0; i < 5; i++) service.listen(event());
        verifyNoInteractions(mail, templates);
        assertThat(meters.get("incident.notification.suppressed").counter().count()).isEqualTo(5);
        assertThat(meters.find("incident.notification.sent").counter()).isNull();
    }

    @Test
    void liveSwitchControlsTheExistingConsumer() {
        var properties = new NotificationProperties(false, "sender@example.invalid", "recipient@example.invalid");
        var control = new EmailControl(properties, "smtp.local", "user", "password", true);
        var consumer = new NotificationService(mail, templates, properties, meters, control);
        consumer.listen(event());
        verifyNoInteractions(mail);
        control.update(true);
        consumer.listen(event());
        verify(mail, times(1)).send(any(MimeMessagePreparator.class));
        control.update(false);
        consumer.listen(event());
        verify(mail, times(1)).send(any(MimeMessagePreparator.class));
    }

    @Test
    void enabledConsumerSendsNotification() {
        service(true).listen(event());
        verify(mail).send(any(MimeMessagePreparator.class));
        assertThat(meters.get("incident.notification.sent").counter().count()).isEqualTo(1);
        assertThat(meters.find("incident.notification.suppressed").counter()).isNull();
    }

    @Test
    void enabledConsumerDoesNotSilentlySwallowSmtpFailure() {
        doThrow(new MailSendException("SMTP unavailable")).when(mail).send(any(MimeMessagePreparator.class));
        assertThatThrownBy(() -> service(true).listen(event())).isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("incident.notification.failed").counter().count()).isEqualTo(1);
        assertThat(meters.find("incident.notification.sent").counter()).isNull();
    }
}
