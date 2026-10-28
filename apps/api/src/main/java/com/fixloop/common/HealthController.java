package com.fixloop.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A lightweight liveness check; Actuator checks the database separately. */
@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "fixloop-api");
    }

    public record HealthResponse(String status, String service) {
    }
}
