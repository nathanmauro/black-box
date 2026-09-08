package dev.nathan.sbaagentic.platform.internal.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
public class WebSecurityConfiguration {
    @Bean
    AuthSettings authSettings(Environment environment) {
        return new AuthSettings(environment);
    }

    @Bean
    UserDetailsService authenticationUsers(AuthSettings settings) {
        if (!settings.enabled()) return new InMemoryUserDetailsManager();
        var encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        return new InMemoryUserDetailsManager(User.withUsername(settings.username())
                .password("{pbkdf2@SpringSecurity_v5_8}" + encoder.encode(settings.password()))
                .roles("USER").build());
    }

    @Bean
    @Order(1)
    SecurityFilterChain agentSecurity(HttpSecurity http, AuthSettings settings) throws Exception {
        byte[] expected = digest(settings.apiToken());
        http.securityMatcher(request -> settings.enabled() && request.getHeader("Authorization") != null)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> unauthorized(response)))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint((request, response, exception) -> unauthorized(response))
                        .opaqueToken(opaque -> opaque.introspector(token -> {
                            if (!MessageDigest.isEqual(expected, digest(token))) {
                                throw new BadOpaqueTokenException("Invalid API token");
                            }
                            return new DefaultOAuth2AuthenticatedPrincipal(settings.username(),
                                    Map.of("sub", settings.username()), AuthorityUtils.createAuthorityList("ROLE_USER"));
                        })));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserSecurity(HttpSecurity http, AuthSettings settings) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(settings.secureCookies()));
        http.securityMatcher(request -> settings.enabled())
                .authorizeHttpRequests(authorize -> authorize
                        // The initial stream request authenticates normally; completion must also
                        // run after logout. EventBroadcaster rechecks the session before each send.
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.ASYNC
                                && request.getServletPath().equals("/api/stream")).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.permitAll()
                        .successHandler((request, response, authentication) -> redirect(response, "/"))
                        .failureHandler((request, response, exception) -> redirect(response, "/login?error")))
                .logout(logout -> logout
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> redirect(response, "/login?logout")))
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor((request, response, exception) -> {
                            if (isApi(request)) unauthorized(response);
                            else redirect(response, "/login");
                        }, request -> true)
                        .accessDeniedHandler((request, response, exception) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            if (isApi(request) && (authentication == null || authentication instanceof AnonymousAuthenticationToken)) {
                                unauthorized(response);
                            } else {
                                jsonError(response, 403, "Forbidden");
                            }
                        }));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }

    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> authenticatedSessionCookies(AuthSettings settings) {
        return factory -> {
            if (!settings.enabled()) return;
            var session = new Session();
            session.setTimeout(Duration.ofMinutes(30));
            session.setTrackingModes(Set.of(Session.SessionTrackingMode.COOKIE));
            session.getCookie().setHttpOnly(true);
            session.getCookie().setSecure(settings.secureCookies());
            session.getCookie().setSameSite(Cookie.SameSite.LAX);
            session.getCookie().setPath("/");
            factory.setSession(session);
        };
    }

    private static boolean isApi(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api") || path.startsWith("/api/") || path.equals("/mcp")
                || path.startsWith("/mcp/") || path.equals("/actuator") || path.startsWith("/actuator/");
    }

    private static void redirect(HttpServletResponse response, String path) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", path);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        jsonError(response, 401, "Authentication required");
    }

    private static void jsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
