package utnfc.isi.back.tpi.solicitudes.clients;

import org.springframework.web.client.RestClientResponseException;
import utnfc.isi.back.tpi.solicitudes.exceptions.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.solicitudes.exceptions.ReglaNegocioException;
import utnfc.isi.back.tpi.solicitudes.exceptions.ServicioRemotoException;

/** Utilidades comunes para traducir los errores de las llamadas REST. */
final class RemoteErrors {

    private RemoteErrors() {
    }

    /** 4xx del otro servicio: se devuelve el mismo status con su detalle. */
    static ServicioRemotoException traducir(String servicio, RestClientResponseException e) {
        return ServicioRemotoException.desde(servicio, e);
    }

    /**
     * El fallback de Resilience4j recibe cualquier excepción. Las de negocio (404, 4xx remotos)
     * no significan que el servicio esté caído, así que se relanzan tal cual.
     */
    static void relanzarSiEsDeNegocio(Throwable causa) {
        if (causa instanceof RecursoNoEncontradoException
                || causa instanceof ReglaNegocioException
                || causa instanceof ServicioRemotoException) {
            throw (RuntimeException) causa;
        }
    }
}
