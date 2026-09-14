package com.micro_service.notification_service.api;

import com.micro_service.notification_service.service.EmailControl;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/notification-settings")
public class EmailSettingsController {
    private final EmailControl control;
    public EmailSettingsController(EmailControl control) { this.control = control; }

    @GetMapping
    public EmailControl.State read() { return control.state(); }

    @PutMapping(consumes = "application/json", headers = "X-Requested-With=incident-ui")
    public EmailControl.State update(@RequestBody Update request) {
        if (request.enabled() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required");
        return control.update(request.enabled());
    }
    public record Update(Boolean enabled) {}
}
