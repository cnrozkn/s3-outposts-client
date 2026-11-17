package io.github.cnrozkn.s3client.config;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Form login for every page. Static assets, the error page and the probe and metrics endpoints of the management
 * port stay open. CSRF protection stays on: forms carry the token, upload requests send it as a header.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final int MIN_PASSWORD_LENGTH = 16;

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Value("${spring.security.user.password}") String password) throws Exception {
        // One shared password guards every bucket and login attempts are not throttled, so it has to be long.
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "S3CLIENT_PASSWORD must have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(
                                "/css/**",
                                "/js/**",
                                "/webjars/**",
                                "/error",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.permitAll())
                .build();
    }

    /**
     * Tomcat counts idle time from the start of the last request, so without the activity check the login ends in
     * the middle of an upload or download that runs longer than the session timeout.
     */
    @Bean
    TomcatContextCustomizer sessionsLastWhileARequestRuns() {
        return context -> {
            Manager manager = context.getManager() == null ? new StandardManager() : context.getManager();
            manager.setSessionActivityCheck(true);
            context.setManager(manager);
        };
    }
}
