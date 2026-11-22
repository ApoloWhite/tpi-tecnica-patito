package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import utnfc.isi.back.tpi.solicitudes.models.ModoAsignacion;
import utnfc.isi.back.tpi.solicitudes.models.Prioridad;
import utnfc.isi.back.tpi.solicitudes.models.TipoServicio;

import java.time.LocalDateTime;
import java.util.Set;

/** Datos para crear una solicitud (F4, RN-1). */
@Schema(description = "Datos de entrada de una solicitud de servicio")
public record SolicitudRequest(
        @Schema(example = "1") @NotNull(message = "El clienteId es obligatorio")
        Long clienteId,

        @Schema(example = "[\"REPARACION\"]", description = "Una, dos o las tres: REPARACION, INSTALACION, MANTENIMIENTO")
        @NotEmpty(message = "Debe indicar al menos un tipo de servicio")
        Set<@NotNull TipoServicio> tiposServicio,

        @Schema(example = "HP") @NotBlank(message = "La marca es obligatoria") @Size(max = 60)
        String marca,

        @Schema(example = "LaserJet 1020") @NotBlank(message = "El modelo es obligatorio") @Size(max = 60)
        String modelo,

        @Schema(example = "No toma las hojas") @NotBlank(message = "La descripción es obligatoria") @Size(max = 500)
        String descripcion,

        @Schema(example = "1", description = "Id de la certificación requerida (ver GET /api/v1/certificaciones)")
        @NotNull(message = "El especialidadId es obligatorio")
        Long especialidadId,

        @Schema(example = "NORMAL") @NotNull(message = "La prioridad es obligatoria")
        Prioridad prioridad,

        @Schema(example = "2026-12-01T10:00:00") @NotNull(message = "La fecha y hora programada es obligatoria")
        LocalDateTime fechaHoraProgramada,

        @Schema(example = "false", description = "Si se omite, se toma como día no feriado")
        Boolean esFeriado,

        @Schema(example = "AUTOMATICA", description = "AUTOMATICA (por defecto): el sistema oferta solo al técnico más cercano. "
                + "MANUAL: se elige con /elegir y /ofertar")
        ModoAsignacion modoAsignacion
) {
}
