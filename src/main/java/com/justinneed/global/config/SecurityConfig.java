package com.justinneed.global.config;

import com.justinneed.auth.jwt.JwtAuthenticationEntryPoint;
import com.justinneed.auth.jwt.JwtAuthenticationFilter;
import com.justinneed.auth.oauth.CustomOAuth2UserService;
import com.justinneed.auth.oauth.OAuth2SuccessHandler;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final String failureRedirectUri;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            CustomOAuth2UserService customOAuth2UserService,
            OAuth2SuccessHandler oAuth2SuccessHandler,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            @Value("${app.oauth.failure-redirect-uri}") String failureRedirectUri,
            @Value("${app.cors.allowed-origins}") String allowedOrigins
    ) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.failureRedirectUri = failureRedirectUri;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        // 공개 엔드포인트
                        .requestMatchers("/", "/error",
                                "/auth/**", "/oauth2/**", "/login/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 헬스체크(로드밸런서·모니터링)용. 그 외 actuator 엔드포인트는 인증 필요
                        .requestMatchers("/actuator/health").permitAll()
                        // 세션·태그그룹 API는 로그인 회원만 (access 토큰의 memberId = user_id)
                        // 그 외(예: /members/**)도 access 토큰 필요
                        .anyRequest().authenticated()
                )
                // 소셜 로그인: /oauth2/authorization/{kakao|naver} 진입 → 콜백 → 프로필 조회 → 토큰 발급
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        // 소셜 로그인 실패 시 원인 로깅 후 FE 로그인 페이지로 (BE에는 /login 화면이 없음)
                        .failureHandler((request, response, exception) -> {
                            log.warn("[OAuth2 login failed] {}", exception.getMessage());
                            response.sendRedirect(failureRedirectUri);
                        })
                )
                // 보호 리소스에 인증 없이 접근 시 리다이렉트 대신 401 + 공통 에러 응답 (API 친화적)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                // 모든 요청 전에 JWT access 토큰 검사
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
