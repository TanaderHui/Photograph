package com.shiguang.photograph.config;

import com.shiguang.photograph.api.ApiErrors;
import com.shiguang.photograph.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth, ApiErrors errors) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, cause) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            errors.security(response, HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请先登录。");
                        })
                        .accessDeniedHandler((request, response, cause) ->
                                errors.security(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号无权执行此操作。")))
                .addFilterBefore(new BearerFilter(auth), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/styles", "/api/v1/app-update",
                                "/api/v1/photographers/{id}",
                                "/api/v1/photographers/{id}/packages",
                                "/api/v1/photographers/{id}/availability",
                                "/api/v1/works", "/api/v1/feed", "/api/v1/posts/{id}",
                                "/api/v1/places", "/api/v1/places/{id}", "/api/v1/map/markers",
                                "/api/v1/search/home").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/otp/challenges", "/api/v1/auth/sessions",
                                "/api/v1/auth/sessions/refresh").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("OPERATOR")
                        .requestMatchers("/api/v1/me/**", "/api/v1/bookings", "/api/v1/bookings/**",
                                "/api/v1/posts", "/api/v1/conversations", "/api/v1/conversations/**",
                                "/api/v1/auth/sessions/logout", "/api/v1/auth/account").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    private static class BearerFilter extends OncePerRequestFilter {
        private final AuthService auth;

        BearerFilter(AuthService auth) { this.auth = auth; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                auth.authenticate(header.substring(7)).ifPresent(principal -> {
                    var authorities = principal.operator()
                            ? List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")) : List.<SimpleGrantedAuthority>of();
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
                    SecurityContextHolder.setContext(context);
                });
            }
            chain.doFilter(request, response);
        }
    }
}
