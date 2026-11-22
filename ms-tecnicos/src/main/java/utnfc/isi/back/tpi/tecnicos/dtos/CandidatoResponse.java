package utnfc.isi.back.tpi.tecnicos.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

/** Técnico que cumple las condiciones de candidatura (RN-2, RN-9), con su distancia al cliente. */
@Schema(description = "Técnico candidato a recibir una oferta")
public record CandidatoResponse(
        @Schema(example = "1") Long tecnicoId,
        @Schema(example = "Juan") String nombre,
        @Schema(example = "Pérez") String apellido,
        @Schema(example = "-31.4167") Double latitud,
        @Schema(example = "-64.1833") Double longitud,
        @Schema(example = "0.58", description = "Distancia en línea recta (Haversine) al cliente") Double distanciaKm
) {
}
