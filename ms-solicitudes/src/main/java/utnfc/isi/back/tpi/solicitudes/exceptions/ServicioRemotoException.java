package utnfc.isi.back.tpi.solicitudes.exceptions;

import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClientResponseException;

/**
 * Otro microservicio respondió con un error 4xx (por ejemplo 400 o 403).
 * Se devuelve el mismo status con el detalle del ProblemDetail remoto.
 */
public class ServicioRemotoException extends RuntimeException {

    private final int status;

    public ServicioRemotoException(int status, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.status = status;
    }

    public static ServicioRemotoException desde(String servicio, RestClientResponseException e) {
        String detalle = e.getStatusText();
        try {
            ProblemDetail remoto = e.getResponseBodyAs(ProblemDetail.class);
            if (remoto != null && remoto.getDetail() != null) {
                detalle = remoto.getDetail();
            }
        } catch (RuntimeException ignorada) {
            // El cuerpo no era un ProblemDetail: se usa el texto del status
            detalle = e.getStatusText();
        }
        return new ServicioRemotoException(e.getStatusCode().value(), servicio + ": " + detalle, e);
    }

    public int getStatus() {
        return status;
    }
}
