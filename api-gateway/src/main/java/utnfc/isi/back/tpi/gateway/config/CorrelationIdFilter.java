package utnfc.isi.back.tpi.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Genera el X-Correlation-ID si no viene en la petición (Ap.23) y lo propaga al microservicio.
 * Corre antes que la seguridad (order -200), así hasta los 401 llevan su correlation id.
 * También registra cada petición con su status y tiempo.
 */
@Component
@Order(-200)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-ID";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = recibido == null || recibido.isBlank() ? UUID.randomUUID().toString() : recibido;

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();
        // Se fija justo antes de responder para reemplazar (no duplicar) el que devuelve el microservicio
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        long inicio = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(senal -> {
                    if (request.getPath().value().startsWith("/api/")) {
                        log.info("[cid:{}] {} {} -> {} ({} ms)", correlationId, request.getMethod(),
                                request.getPath().value(), exchange.getResponse().getStatusCode(),
                                System.currentTimeMillis() - inicio);
                    }
                });
    }
}
