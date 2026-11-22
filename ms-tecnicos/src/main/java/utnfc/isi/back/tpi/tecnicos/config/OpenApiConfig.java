package utnfc.isi.back.tpi.tecnicos.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Documentación Swagger / OpenAPI (Ap.14, 17).
 * El botón "Authorize" permite pegar el token de Keycloak para probar los endpoints.
 * El server "/" hace que "Try it out" funcione tanto directo como a través del gateway.
 */
@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA = "bearer-jwt";

    @Bean
    public OpenAPI openApi(@Value("${app.openapi.titulo}") String titulo,
                           @Value("${app.openapi.descripcion}") String descripcion) {
        return new OpenAPI()
                .info(new Info().title(titulo).description(descripcion).version("v1"))
                .servers(List.of(new Server().url("/").description("Servidor actual")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA))
                .components(new Components().addSecuritySchemes(ESQUEMA, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Token de Keycloak (realm patito). Pegalo sin la palabra Bearer.")));
    }
}
