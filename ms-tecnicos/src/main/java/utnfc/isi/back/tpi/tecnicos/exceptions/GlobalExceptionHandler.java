package utnfc.isi.back.tpi.tecnicos.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import utnfc.isi.back.tpi.tecnicos.config.CorrelationIdFilter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manejo global de errores con formato ProblemDetail (RFC 7807, Ap.17 y 18).
 * 400 = datos mal formados, 403 = sin permiso, 404 = no existe, 422 = regla de negocio, 500 = inesperado.
 * Extiende ResponseEntityExceptionHandler para que los errores estándar de Spring MVC
 * (JSON roto, parámetro faltante, método no soportado, etc.) también salgan como ProblemDetail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Object> manejarNoEncontrado(RecursoNoEncontradoException ex) {
        log.warn("Recurso no encontrado: {}", ex.getMessage());
        return problema(HttpStatus.NOT_FOUND, "Recurso no encontrado", ex.getMessage());
    }

    @ExceptionHandler(ReglaNegocioException.class)
    public ResponseEntity<Object> manejarReglaNegocio(ReglaNegocioException ex) {
        log.warn("Regla de negocio incumplida: {}", ex.getMessage());
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Regla de negocio incumplida", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> manejarIntegridad(DataIntegrityViolationException ex) {
        log.warn("Violación de integridad de datos: {}", ex.getMostSpecificCause().getMessage());
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Dato duplicado o inválido",
                "La operación viola una restricción de datos (por ejemplo, un valor único repetido)");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> manejarConcurrencia(ObjectOptimisticLockingFailureException ex) {
        log.warn("Conflicto de concurrencia: {}", ex.getMessage());
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Conflicto de concurrencia",
                "El recurso fue modificado por otra operación al mismo tiempo. Volvé a intentarlo.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> manejarAccesoDenegado(AccessDeniedException ex) {
        log.warn("Acceso denegado: {}", ex.getMessage());
        return problema(HttpStatus.FORBIDDEN, "Acceso denegado",
                "Tu rol no tiene permiso para realizar esta operación");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> manejarInesperado(Exception ex) {
        log.error("Error inesperado", ex);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Ocurrió un error inesperado. Informá el correlationId al equipo de soporte.");
    }

    /** 400 con el detalle de cada campo inválido. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errores = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errores.putIfAbsent(error.getField(), error.getDefaultMessage()));
        log.warn("Validación fallida: {}", errores);
        ProblemDetail problema = crearProblema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "Uno o más campos no cumplen las validaciones");
        problema.setProperty("errores", errores);
        return ResponseEntity.badRequest().body(problema);
    }

    /** 400 cuando un parámetro de la URL (@RequestParam, @PathVariable) no cumple sus restricciones. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        Map<String, String> errores = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(resultado -> errores.putIfAbsent(
                resultado.getMethodParameter().getParameterName(),
                resultado.getResolvableErrors().isEmpty() ? "valor inválido"
                        : resultado.getResolvableErrors().getFirst().getDefaultMessage()));
        log.warn("Parámetros inválidos: {}", errores);
        ProblemDetail problema = crearProblema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "Uno o más parámetros no cumplen las validaciones");
        problema.setProperty("errores", errores);
        return ResponseEntity.badRequest().body(problema);
    }

    /** Agrega timestamp y correlationId también a los errores estándar de Spring MVC. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> respuesta = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (respuesta != null && respuesta.getBody() instanceof ProblemDetail problema) {
            completar(problema);
        }
        return respuesta;
    }

    private ResponseEntity<Object> problema(HttpStatus status, String titulo, String detalle) {
        return ResponseEntity.status(status).body(crearProblema(status, titulo, detalle));
    }

    private ProblemDetail crearProblema(HttpStatus status, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setTitle(titulo);
        completar(problema);
        return problema;
    }

    private void completar(ProblemDetail problema) {
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
