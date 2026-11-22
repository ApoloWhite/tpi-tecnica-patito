package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud;
import utnfc.isi.back.tpi.solicitudes.models.HistorialEstado;

import java.time.LocalDateTime;

/** Una transición de estado de la solicitud (auditoría RN-7). */
@Schema(description = "Registro del historial de estados")
public record HistorialResponse(
        Long id,
        EstadoSolicitud estadoAnterior,
        EstadoSolicitud estadoNuevo,
        LocalDateTime fechaHora,
        @Schema(example = "Cliente aprobó presupuesto por $1500.00") String observaciones,
        @Schema(example = "cliente1") String usuario
) {
    public static HistorialResponse desde(HistorialEstado h) {
        return new HistorialResponse(h.getId(), h.getEstadoAnterior(), h.getEstadoNuevo(), h.getFechaHora(),
                h.getObservaciones(), h.getUsuario());
    }
}
