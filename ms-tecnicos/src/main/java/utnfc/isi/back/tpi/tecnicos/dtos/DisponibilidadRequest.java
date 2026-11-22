package utnfc.isi.back.tpi.tecnicos.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Cambio de disponibilidad de un técnico. */
public record DisponibilidadRequest(
        @Schema(example = "false") @NotNull(message = "Indicá si el técnico está disponible") Boolean disponible
) {
}
