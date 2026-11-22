package utnfc.isi.back.tpi.geo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;

/**
 * Consulta la API pública de OSRM para obtener la distancia y el tiempo reales por calle.
 * Si OSRM no responde (timeout, caída, sin internet) usa Haversine como respaldo (RN-8),
 * así una falla externa nunca rompe el flujo de negocio.
 */
public class OsrmGeoService implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(OsrmGeoService.class);

    private final RestClient restClient;
    private final boolean habilitado;

    public OsrmGeoService(RestClient restClient, boolean habilitado) {
        this.restClient = restClient;
        this.habilitado = habilitado;
    }

    @Override
    public Ruta calcularRuta(double latOrigen, double lonOrigen, double latDestino, double lonDestino) {
        if (!habilitado) {
            log.debug("OSRM deshabilitado por configuración, se usa Haversine");
            return Haversine.ruta(latOrigen, lonOrigen, latDestino, lonDestino);
        }
        // OSRM espera las coordenadas como longitud,latitud
        String coordenadas = String.format(Locale.US, "%f,%f;%f,%f", lonOrigen, latOrigen, lonDestino, latDestino);
        try {
            OsrmRespuesta respuesta = restClient.get()
                    .uri("/route/v1/driving/{coordenadas}?overview=false", coordenadas)
                    .retrieve()
                    .body(OsrmRespuesta.class);
            if (respuesta == null || !"Ok".equals(respuesta.code()) || respuesta.routes() == null
                    || respuesta.routes().isEmpty()) {
                log.warn("OSRM respondió sin rutas válidas (code={}), se usa Haversine",
                        respuesta == null ? null : respuesta.code());
                return Haversine.ruta(latOrigen, lonOrigen, latDestino, lonDestino);
            }
            OsrmRuta ruta = respuesta.routes().getFirst();
            double km = Haversine.redondear(ruta.distance() / 1000.0);
            int minutos = (int) Math.ceil(ruta.duration() / 60.0);
            log.info("Ruta OSRM calculada: {} km, {} min", km, minutos);
            return new Ruta(km, minutos, Ruta.Fuente.OSRM);
        } catch (RestClientException e) {
            log.warn("OSRM no disponible ({}), se usa Haversine como respaldo", e.getMessage());
            return Haversine.ruta(latOrigen, lonOrigen, latDestino, lonDestino);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRespuesta(String code, List<OsrmRuta> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRuta(double distance, double duration) {
    }
}
