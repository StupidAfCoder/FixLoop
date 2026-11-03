package com.fixloop.auth;

import com.fixloop.auth.dto.TokenResponse;
import com.fixloop.common.RequestRateLimiter;
import com.fixloop.config.AuthConfiguration;
import com.fixloop.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins which identity endpoints may be called without an access token. This is
 * the bug class that the smoke test caught: logout was accidentally locked, so
 * a client whose access token had expired could not end its session.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, AuthConfiguration.class})
class AuthControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RequestRateLimiter rateLimiter;

    @Test
    void logoutWorksWithoutAnAccessTokenBecauseTheRefreshTokenIsTheCredential() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"a-refresh-token\"}"))
            .andExpect(status().isNoContent());

        verify(authService).logout("a-refresh-token");
    }

    @Test
    void logoutStillValidatesItsBody() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginIsPublic() throws Exception {
        when(authService.login("student@bmu.edu.in", "campus-lamp-2026"))
            .thenReturn(TokenResponse.bearer("an-access-token", 900, "a-refresh-token"));

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@bmu.edu.in\",\"password\":\"campus-lamp-2026\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("an-access-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void changingAPasswordStillRequiresAnAccessToken() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"campus-lamp-2026\","
                    + "\"newPassword\":\"library-window-2027\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void readingYourOwnAccountStillRequiresAnAccessToken() throws Exception {
        mvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized());
    }
}
