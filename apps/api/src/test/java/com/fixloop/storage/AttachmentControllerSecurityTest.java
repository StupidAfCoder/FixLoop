package com.fixloop.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fixloop.auth.AuthService;
import com.fixloop.common.RequestRateLimiter;
import com.fixloop.config.AuthConfiguration;
import com.fixloop.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Uploading a photograph needs a signed-in account and nothing more. */
@WebMvcTest(AttachmentController.class)
@Import({SecurityConfiguration.class, AuthConfiguration.class})
class AttachmentControllerSecurityTest {
    private static final String STUDENT_ID = "3f1a6c1e-1111-4a2b-9a10-0c2b7d5e4f60";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AttachmentService attachments;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RequestRateLimiter rateLimiter;

    private static MockMultipartFile photo() {
        return new MockMultipartFile("file", "cracked-wall.png", "image/png", AttachmentPolicyTest.png());
    }

    @Test
    void refusesAnUploadWithoutAToken() throws Exception {
        mvc.perform(multipart("/api/v1/attachments").file(photo()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void acceptsAPhotographFromASignedInStudent() throws Exception {
        when(attachments.store(eq("image/png"), any(byte[].class), any(UUID.class)))
            .thenReturn(new StoredAttachment("ab/cd/11111111-2222-4333-8444-555555555555.png",
                "image/png", 64, "a".repeat(64)));

        mvc.perform(multipart("/api/v1/attachments").file(photo())
                .with(jwt().jwt(token -> token.subject(STUDENT_ID))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.mimeType").value("image/png"))
            .andExpect(jsonPath("$.sizeBytes").value(64))
            .andExpect(jsonPath("$.checksumSha256").value("a".repeat(64)));

        // The allowance is per account, so one student cannot exhaust another's.
        verify(rateLimiter).check(startsWith("attachment:"), anyInt(), any(java.time.Duration.class));
    }
}
