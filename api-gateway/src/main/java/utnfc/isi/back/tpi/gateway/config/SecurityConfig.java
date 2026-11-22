package utnfc.isi.back.tpi.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Primer punto de control (Ap.19, 20): el gateway es Resource Server y rechaza con 401
 * toda petición a /api/** sin un JWT válido de Keycloak. La autorización fina por rol
 * (403) la hace cada microservicio con @PreAuthorize.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/webjars/**",
                                "/v3/api-docs/**", "/docs/**").permitAll()
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
                        .authenticationEntryPoint((exchange, ex) -> escribirProblema(exchange, HttpStatus.UNAUTHORIZED,
                                "No autenticado",
                                "Se requiere un token JWT válido en el header Authorization: Bearer <token>"))
                        .accessDeniedHandler((exchange, ex) -> escribirProblema(exchange, HttpStatus.FORBIDDEN,
                                "Acceso denegado", "Tu rol no tiene permiso para acceder a este recurso")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((exchange, ex) -> escribirProblema(exchange, HttpStatus.UNAUTHORIZED,
                                "No autenticado",
                                "Se requiere un token JWT válido en el header Authorization: Bearer <token>"))
                        .accessDeniedHandler((exchange, ex) -> escribirProblema(exchange, HttpStatus.FORBIDDEN,
                                "Acceso denegado", "La ruta no existe o no está permitida")));
        return http.build();
    }

    /** Convierte el JWT de Keycloak en un usuario de Spring con sus roles. */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extraerRoles);
        converter.setPrincipalClaimName("preferred_username");
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /** Keycloak manda los roles en el claim "realm_access": { "roles": [...] }. */
    private Collection<GrantedAuthority> extraerRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r.toString().toUpperCase()))
                .toList();
    }

    private Mono<Void> escribirProblema(ServerWebExchange exchange, HttpStatus status, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(problema);
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            exchange.getResponse().setStatusCode(status);
            return exchange.getResponse().setComplete();
        }
    }
}
