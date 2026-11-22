package utnfc.isi.back.tpi.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator configurarRutas(
            RouteLocatorBuilder builder,
            @Value("${app.routes.clientes-url}") String urlClientes,
            @Value("${app.routes.tecnicos-url}") String urlTecnicos,
            @Value("${app.routes.solicitudes-url}") String urlSolicitudes) {

        return builder.routes()
                .route("ms-clientes", r -> r.path("/api/v1/clientes/**").uri(urlClientes))
                .route("ms-tecnicos", r -> r.path("/api/v1/tecnicos/**").uri(urlTecnicos))
                .route("ms-solicitudes", r -> r.path("/api/v1/solicitudes/**").uri(urlSolicitudes))
                .build();
    }
}
