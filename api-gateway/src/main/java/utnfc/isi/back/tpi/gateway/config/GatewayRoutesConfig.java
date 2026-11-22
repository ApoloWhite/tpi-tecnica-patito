package utnfc.isi.back.tpi.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rutas del gateway (Ap.19). El cliente solo conoce el puerto 47380: el gateway decide
 * a qué microservicio mandar cada petición según el path. Las URLs salen de la configuración.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator configurarRutas(
            RouteLocatorBuilder builder,
            @Value("${app.routes.clientes-url}") String urlClientes,
            @Value("${app.routes.tecnicos-url}") String urlTecnicos,
            @Value("${app.routes.solicitudes-url}") String urlSolicitudes) {

        return builder.routes()
                // --- API de negocio ---
                .route("ms-clientes", r -> r.path("/api/v1/clientes", "/api/v1/clientes/**").uri(urlClientes))
                .route("ms-tecnicos", r -> r.path("/api/v1/tecnicos", "/api/v1/tecnicos/**",
                        "/api/v1/certificaciones", "/api/v1/zonas").uri(urlTecnicos))
                .route("ms-solicitudes", r -> r.path("/api/v1/solicitudes", "/api/v1/solicitudes/**").uri(urlSolicitudes))
                // --- Documentación OpenAPI de cada microservicio, para el Swagger unificado ---
                .route("docs-clientes", r -> r.path("/docs/clientes/v3/api-docs")
                        .filters(f -> f.setPath("/v3/api-docs")).uri(urlClientes))
                .route("docs-tecnicos", r -> r.path("/docs/tecnicos/v3/api-docs")
                        .filters(f -> f.setPath("/v3/api-docs")).uri(urlTecnicos))
                .route("docs-solicitudes", r -> r.path("/docs/solicitudes/v3/api-docs")
                        .filters(f -> f.setPath("/v3/api-docs")).uri(urlSolicitudes))
                .build();
    }
}
