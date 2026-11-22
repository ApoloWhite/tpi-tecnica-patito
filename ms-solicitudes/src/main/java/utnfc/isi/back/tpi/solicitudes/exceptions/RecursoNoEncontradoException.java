package utnfc.isi.back.tpi.solicitudes.exceptions;

/** El recurso pedido no existe. Se traduce a 404. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
