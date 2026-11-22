package utnfc.isi.back.tpi.solicitudes.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import utnfc.isi.back.tpi.solicitudes.dtos.CandidatoDto;
import utnfc.isi.back.tpi.solicitudes.dtos.TecnicoDto;
import utnfc.isi.back.tpi.solicitudes.exceptions.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.solicitudes.exceptions.ServicioNoDisponibleException;

import java.util.List;

/** Cliente REST hacia MS-Técnicos, con el mismo esquema de resiliencia que {@link ClientesClient}. */
@Component
public class TecnicosClient {

    private static final Logger log = LoggerFactory.getLogger(TecnicosClient.class);

    private final RestClient restClient;

    public TecnicosClient(@Qualifier("tecnicosRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = "tecnicos")
    @Retry(name = "tecnicos", fallbackMethod = "sinServicioTecnico")
    public TecnicoDto obtener(Long tecnicoId) {
        try {
            return restClient.get()
                    .uri("/api/v1/tecnicos/{tecnicoId}", tecnicoId)
                    .retrieve()
                    .body(TecnicoDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontradoException("No existe el técnico con id " + tecnicoId);
        } catch (HttpClientErrorException e) {
            throw RemoteErrors.traducir("ms-tecnicos", e);
        }
    }

    @CircuitBreaker(name = "tecnicos")
    @Retry(name = "tecnicos", fallbackMethod = "sinServicioCandidatos")
    public List<CandidatoDto> candidatos(Long certificacionId, double latitud, double longitud) {
        try {
            List<CandidatoDto> candidatos = restClient.get()
                    .uri(uri -> uri.path("/api/v1/tecnicos/candidatos")
                            .queryParam("certificacionId", certificacionId)
                            .queryParam("latitud", latitud)
                            .queryParam("longitud", longitud)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CandidatoDto>>() {
                    });
            return candidatos == null ? List.of() : candidatos;
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontradoException("No existe la certificación (especialidad) con id " + certificacionId);
        } catch (HttpClientErrorException e) {
            throw RemoteErrors.traducir("ms-tecnicos", e);
        }
    }

    @SuppressWarnings("unused")
    private TecnicoDto sinServicioTecnico(Long tecnicoId, Throwable causa) {
        RemoteErrors.relanzarSiEsDeNegocio(causa);
        log.warn("ms-tecnicos no disponible al consultar el técnico {}: {}", tecnicoId, causa.toString());
        throw new ServicioNoDisponibleException("El servicio de técnicos no se encuentra disponible", causa);
    }

    @SuppressWarnings("unused")
    private List<CandidatoDto> sinServicioCandidatos(Long certificacionId, double latitud, double longitud,
                                                     Throwable causa) {
        RemoteErrors.relanzarSiEsDeNegocio(causa);
        log.warn("ms-tecnicos no disponible al buscar candidatos: {}", causa.toString());
        throw new ServicioNoDisponibleException("El servicio de técnicos no se encuentra disponible", causa);
    }
}
