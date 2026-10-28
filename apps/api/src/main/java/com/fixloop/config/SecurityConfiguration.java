package com.fixloop.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixloop.common.ApiError;
import com.fixloop.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** No domain endpoint is usable until real authentication and authorization exist. */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/v1/health", "/actuator/health", "/actuator/health/**",
                    "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                .permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeError(
                    objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHENTICATED", "Authentication is not available yet."))
                .accessDeniedHandler((request, response, exception) -> writeError(
                    objectMapper, request, response, HttpServletResponse.SC_FORBIDDEN,
                    "FORBIDDEN", "Access is not allowed.")))
            .headers(Customizer.withDefaults());
        return http.build();
    }

    // An empty user store prevents Spring Boot from creating a default login.
    // Replace this when the project's real authentication is implemented.
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
