package utnfc.isi.back.tpi.solicitudes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.io.IOException;

/**
 * Un RestClient por microservicio destino (Ap.18), con:
 * <ul>
 *   <li>baseUrl tomada de la configuración (en Docker: http://ms-clientes:47383, etc.).</li>
 *   <li>Timeouts de conexión y lectura, para no quedar colgados si el otro servicio no responde.</li>
 *   <li>Propagación del token JWT del usuario y del X-Correlation-ID. Si no hay usuario (por ejemplo,
 *       el scheduler que vence ofertas) se usa el token de la cuenta de servicio de ms-solicitudes.</li>
 *   <li>Log de cada llamada saliente: método, URI, status y tiempo (sin datos sensibles).</li>
 * </ul>
 */
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);
    private static final String CUENTA_SERVICIO = "ms-solicitudes";

    private final OAuth2AuthorizedClientManager cuentaServicio;

    public RestClientConfig(ClientRegistrationRepository registros, OAuth2AuthorizedClientService clientes) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registros, clientes);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        this.cuentaServicio = manager;
    }

    @Bean
    public RestClient clientesRestClient(RestClient.Builder builder,
                                         @Value("${app.clientes.base-url}") String baseUrl,
                                         SimpleClientHttpRequestFactory requestFactory) {
        return construir(builder, baseUrl, requestFactory);
    }

    @Bean
    public RestClient tecnicosRestClient(RestClient.Builder builder,
                                         @Value("${app.tecnicos.base-url}") String baseUrl,
                                         SimpleClientHttpRequestFactory requestFactory) {
        return construir(builder, baseUrl, requestFactory);
    }

    @Bean
    public SimpleClientHttpRequestFactory requestFactory(
            @Value("${app.http.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.http.read-timeout-ms:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    private RestClient construir(RestClient.Builder builder, String baseUrl,
                                 SimpleClientHttpRequestFactory requestFactory) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(propagarContexto())
                .requestInterceptor(registrarLlamada())
                .build();
    }

    /** Copia el token (del usuario o de la cuenta de servicio) y el correlation id en la llamada saliente. */
    private ClientHttpRequestInterceptor propagarContexto() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
            } else {
                String tokenServicio = tokenCuentaServicio();
                if (tokenServicio != null) {
                    request.getHeaders().setBearerAuth(tokenServicio);
                }
            }
            return execution.execute(request, body);
        };
    }

    /** Token client_credentials de Keycloak. Spring lo guarda y lo renueva solo cuando vence. */
    private String tokenCuentaServicio() {
        try {
            OAuth2AuthorizedClient cliente = cuentaServicio.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(CUENTA_SERVICIO).principal(CUENTA_SERVICIO).build());
            return cliente == null ? null : cliente.getAccessToken().getTokenValue();
        } catch (RuntimeException e) {
            log.warn("No se pudo obtener el token de la cuenta de servicio: {}", e.getMessage());
            return null;
        }
    }

    private static ClientHttpRequestInterceptor registrarLlamada() {
        return (request, body, execution) -> {
            long inicio = System.currentTimeMillis();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                log.info("Llamada saliente {} {} -> {} ({} ms)", request.getMethod(), request.getURI(),
                        response.getStatusCode().value(), System.currentTimeMillis() - inicio);
                return response;
            } catch (IOException e) {
                log.warn("Llamada saliente {} {} falló tras {} ms: {}", request.getMethod(), request.getURI(),
                        System.currentTimeMillis() - inicio, e.getMessage());
                throw e;
            }
        };
    }
}
