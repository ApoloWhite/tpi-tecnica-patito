package utnfc.isi.back.tpi.tecnicos.exceptions;

/** Se incumplió una regla de negocio (por ejemplo, DNI duplicado). Se traduce a 422. */
public class ReglaNegocioException extends RuntimeException {

    public ReglaNegocioException(String mensaje) {
        super(mensaje);
    }
}
