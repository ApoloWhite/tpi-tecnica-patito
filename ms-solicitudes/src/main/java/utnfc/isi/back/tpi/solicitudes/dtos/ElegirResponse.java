package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Resultado de la búsqueda: el recomendado (el más cercano) y todos los candidatos válidos. */
@Schema(description = "Resultado de elegir candidatos")
public record ElegirResponse(
        @Schema(example = "1") int cantidad,
        OpcionTecnicoResponse recomendado,
        List<OpcionTecnicoResponse> candidatos,
        @Schema(example = "Se encontraron 1 técnicos candidatos") String mensaje
) {
}
