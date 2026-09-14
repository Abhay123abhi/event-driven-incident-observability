package com.microobserve.incident.api;

import com.microobserve.incident.model.IncidentRecord;
import com.microobserve.incident.model.IncidentStatus;
import com.microobserve.incident.service.IncidentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/incidents")
@Validated
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping("/webhooks/alertmanager")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public List<IncidentAccepted> receiveAlert(@Valid @RequestBody AlertmanagerWebhook webhook) {
        return webhook.alerts().stream()
                .map(incidentService::receive)
                .map(IncidentAccepted::new)
                .toList();
    }

    @GetMapping
    public IncidentPage incidents(
            @RequestParam(defaultValue = "active") String scope,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return IncidentPage.from(incidentService.incidents(scope, page, size));
    }

    @GetMapping("/{id}")
    public IncidentView incident(@org.springframework.web.bind.annotation.PathVariable String id) {
        return IncidentView.from(incidentService.incident(id));
    }

    public record IncidentAccepted(String incidentId) {
    }

    public record IncidentPage(
            List<IncidentView> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {

        static IncidentPage from(Page<IncidentRecord> incidents) {
            return new IncidentPage(incidents.getContent().stream().map(IncidentView::from).toList(),
                    incidents.getNumber(), incidents.getSize(), incidents.getTotalElements(), incidents.getTotalPages());
        }
    }

    public record IncidentView(
            String incidentId,
            String service,
            String alertName,
            IncidentStatus status,
            String severity,
            String title,
            List<String> evidence,
            List<String> affectedServices,
            List<String> recommendations,
            java.time.Instant detectedAt,
            java.time.Instant updatedAt,
            java.time.Instant resolvedAt) {

        static IncidentView from(IncidentRecord incident) {
            return new IncidentView(incident.id(), incident.service(), incident.alertName(), incident.status(),
                    incident.severity(), incident.title(),
                    incident.evidence(), incident.affectedServices(), incident.recommendations(),
                    incident.detectedAt(), incident.updatedAt(), incident.resolvedAt());
        }
    }
}
