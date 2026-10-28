package com.fixloop.common;

import com.fixloop.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfiguration.class)
class HealthControllerTest {
    private static final String UUID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsPublicAndHasARequestTrace() throws Exception {
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("fixloop-api"))
            .andExpect(header().string(TraceIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void domainPathsAreClosedWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/complaints"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));
    }

    @Test
    @WithMockUser
    void evenAnAuthenticatedUserCannotAccessUnimplementedDomainPaths() throws Exception {
        mvc.perform(get("/api/v1/complaints"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void nonHealthActuatorEndpointsAreClosed() throws Exception {
        mvc.perform(get("/actuator/env"))
            .andExpect(status().isUnauthorized());
    }
}
