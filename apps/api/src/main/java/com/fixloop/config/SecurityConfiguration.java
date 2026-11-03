package com.fixloop.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixloop.common.ApiError;
import com.fixloop.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny by default (bible 14.2). Only health, API docs, and the three endpoints
 * that cannot require a token are public; everything else needs a valid access
 * token, and paths without a handler still stay closed.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    private static final String[] PUBLIC_PATHS = {
        "/api/v1/health",
        "/actuator/health", "/actuator/health/**",
        "/v3/api-docs", "/v3/api-docs/**",
        "/swagger-ui.html", "/swagger-ui/**",
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
        // Logout is authenticated by the refresh token in the body, so it must not
        // need a live access token: an expired access token must still be able to
        // end a session. The response is always 204, so it reveals nothing.
        "/api/v1/auth/logout"
    };

    private static final String[] AUTHENTICATED_PATHS = {
        "/api/v1/me", "/api/v1/auth/change-password",
        // Photograph uploads: a signed-in account only. Reads arrive with the
        // complaint endpoints, where ownership can be checked.
        "/api/v1/attachments"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> writeError(
            objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
            "UNAUTHENTICATED", "Authentication is required.");
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> writeError(
            objectMapper, request, response, HttpServletResponse.SC_FORBIDDEN,
            "FORBIDDEN", "Access is not allowed.");

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers(AUTHENTICATED_PATHS).authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(entryPoint)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .headers(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Origins allowed to call the API from a browser, for example the Flutter
     * web build. Empty by default: no cross-origin access until a deployment
     * names its own origin (bible 14.2).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${fixloop.web.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of(TraceIdFilter.HEADER));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // An empty user store prevents Spring Boot from creating a default login.
    // Authentication is bearer-token only; this bean exists for that reason alone.
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    private static void writeError(ObjectMapper mapper, HttpServletRequest request,
                                   HttpServletResponse response, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
            new ApiError(code, message, (String) request.getAttribute(TraceIdFilter.ATTRIBUTE)));
    }
}
