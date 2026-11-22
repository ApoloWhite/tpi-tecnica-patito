package utnfc.isi.back.tpi.solicitudes.exceptions;

/** Un microservicio del que dependemos no responde (caído o con timeout). Se traduce a 503. */
public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
