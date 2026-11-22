package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import utnfc.isi.back.tpi.solicitudes.models.Prioridad;

import java.time.LocalDateTime;

/** Datos para probar candidatos y costos antes de crear u ofertar una solicitud. */
@Schema(description = "Simulación de candidatos y costo")
public record ElegirRequest(
        @Schema(example = "1", description = "Id de la certificación requerida") @NotNull(message = "El especialidadId es obligatorio")
        Long especialidadId,

        @Schema(example = "-31.420") @NotNull(message = "La latitud es obligatoria")
        @DecimalMin(value = "-90.0", message = "La latitud debe estar entre -90 y 90")
        @DecimalMax(value = "90.0", message = "La latitud debe estar entre -90 y 90")
        Double latitud,

        @Schema(example = "-64.188") @NotNull(message = "La longitud es obligatoria")
        @DecimalMin(value = "-180.0", message = "La longitud debe estar entre -180 y 180")
        @DecimalMax(value = "180.0", message = "La longitud debe estar entre -180 y 180")
        Double longitud,

        @Schema(example = "NORMAL") @NotNull(message = "La prioridad es obligatoria")
        Prioridad prioridad,

        @Schema(example = "2026-12-01T10:00:00") @NotNull(message = "La fecha y hora programada es obligatoria")
        LocalDateTime fechaHoraProgramada,

        @Schema(example = "false") Boolean esFeriado
) {
}
