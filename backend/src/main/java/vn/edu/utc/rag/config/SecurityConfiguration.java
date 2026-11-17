package vn.edu.utc.rag.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import vn.edu.utc.rag.exception.GlobalExceptionHandler;

@Configuration
public class SecurityConfiguration {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  CsrfTokenRepository csrfRepository() {
    var r = new HttpSessionCsrfTokenRepository();
    r.setHeaderName("X-CSRF-TOKEN");
    return r;
  }

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http, CsrfTokenRepository csrf, GlobalExceptionHandler problems)
      throws Exception {
    return http.formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .csrf(c -> c.csrfTokenRepository(csrf))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/**",
                        "/openapi.json",
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, x) ->
                            problems.write(req, res, 401, "UNAUTHENTICATED", "Sign in required."))
                    .accessDeniedHandler(
                        (req, res, x) ->
                            problems.write(
                                req,
                                res,
                                403,
                                x instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                "Request is not permitted.")))
        .build();
  }
}
