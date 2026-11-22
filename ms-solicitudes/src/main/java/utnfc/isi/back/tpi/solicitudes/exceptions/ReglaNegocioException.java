package utnfc.isi.back.tpi.solicitudes.exceptions;

/** Se incumplió una regla de negocio (transición inválida, RN-5, oferta vencida, etc.). Se traduce a 422. */
public class ReglaNegocioException extends RuntimeException {

    public ReglaNegocioException(String mensaje) {
        super(mensaje);
    }
}
