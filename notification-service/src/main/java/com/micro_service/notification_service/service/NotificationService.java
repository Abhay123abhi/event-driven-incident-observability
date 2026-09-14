package com.micro_service.notification_service.service;

import com.micro_service.notification_service.config.NotificationProperties;
import com.micro_service.notification_service.api.IncidentEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Year;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JavaMailSender javaMailSender;
    private final TemplateEngine templateEngine;
    private final NotificationProperties properties;
    private final MeterRegistry meters;
    private final EmailControl control;

    public NotificationService(JavaMailSender javaMailSender, TemplateEngine templateEngine,
                               NotificationProperties properties, MeterRegistry meters, EmailControl control) {
        this.javaMailSender = javaMailSender;
        this.templateEngine = templateEngine;
        this.properties = properties;
        this.meters = meters;
        this.control = control;
    }

    @KafkaListener(topics = "${incident.topic:incident-notification}")
    public void listen(IncidentEvent incident) {
        if (!control.enabled()) {
            meters.counter("incident.notification.suppressed", "service", incident.service(),
                    "status", incident.status()).increment();
            log.debug("Email disabled; skipped notification for incident {}", incident.incidentId());
            return;
        }
        log.info("Processing {} incident {} for {}", incident.status(), incident.incidentId(), incident.service());
        MimeMessagePreparator messagePreparator = mimeMessage -> {
            var messageHelper = new MimeMessageHelper(mimeMessage, StandardCharsets.UTF_8.name());

            messageHelper.setFrom(properties.from());
            messageHelper.setTo(properties.recipient());
            messageHelper.setSubject("[%s] [%s] %s".formatted(incident.status(), incident.severity(), incident.title()));

            var context = new Context();
            context.setVariable("incident", incident);
            context.setVariable("year", Year.now().getValue());

            messageHelper.setText(templateEngine.process("incident-alert", context), true);
        };
        try {
            javaMailSender.send(messagePreparator);
            meters.counter("incident.notification.sent", "service", incident.service(), "status", incident.status()).increment();
            log.info("Incident notification sent for incident {}", incident.incidentId());
        } catch (MailException e) {
            meters.counter("incident.notification.failed", "service", incident.service()).increment();
            log.error("Failed to send incident notification {}", incident.incidentId(), e);
            throw new IllegalStateException("Failed to send incident notification", e);
        }
    }
}
