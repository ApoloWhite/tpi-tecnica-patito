package utnfc.isi.back.tpi.geo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuración: cualquier microservicio que agregue commons-geo como dependencia
 * recibe un bean {@link GeoService} listo para usar, sin configurar nada a mano.
 * <p>
 * Propiedades (todas con valor por defecto):
 * app.geo.base-url, app.geo.enabled, app.geo.connect-timeout-ms, app.geo.read-timeout-ms
 */
@AutoConfiguration
public class GeoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GeoService geoService(
            @Value("${app.geo.base-url:https://router.project-osrm.org}") String baseUrl,
            @Value("${app.geo.enabled:true}") boolean habilitado,
            @Value("${app.geo.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.geo.read-timeout-ms:3000}") int readTimeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        return new OsrmGeoService(restClient, habilitado);
    }
}
