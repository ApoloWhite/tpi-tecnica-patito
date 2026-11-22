package utnfc.isi.back.tpi.solicitudes.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import utnfc.isi.back.tpi.solicitudes.dtos.ClienteDto;
import utnfc.isi.back.tpi.solicitudes.exceptions.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.solicitudes.exceptions.ServicioNoDisponibleException;

/**
 * Cliente REST hacia MS-Clientes.
 * Si el servicio está caído o no responde a tiempo: reintenta una vez y, si sigue fallando,
 * responde 503 (EN-09). Si el circuit breaker está abierto, falla rápido sin llamar.
 */
@Component
public class ClientesClient {

    private static final Logger log = LoggerFactory.getLogger(ClientesClient.class);

    private final RestClient restClient;

    public ClientesClient(@Qualifier("clientesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = "clientes")
    @Retry(name = "clientes", fallbackMethod = "sinServicio")
    public ClienteDto obtener(Long clienteId) {
        try {
            return restClient.get()
                    .uri("/api/v1/clientes/{clienteId}", clienteId)
                    .retrieve()
                    .body(ClienteDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontradoException("No existe el cliente con id " + clienteId);
        } catch (HttpClientErrorException e) {
            throw RemoteErrors.traducir("ms-clientes", e);
        }
    }

    @SuppressWarnings("unused")
    private ClienteDto sinServicio(Long clienteId, Throwable causa) {
        RemoteErrors.relanzarSiEsDeNegocio(causa);
        log.warn("ms-clientes no disponible al consultar el cliente {}: {}", clienteId, causa.toString());
        throw new ServicioNoDisponibleException("El servicio de clientes no se encuentra disponible", causa);
    }
}
