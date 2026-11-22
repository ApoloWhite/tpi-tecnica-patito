package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** Un técnico candidato con el costo de visita estimado. */
@Schema(description = "Candidato con costo estimado")
public record OpcionTecnicoResponse(
        @Schema(example = "1") Long tecnicoId,
        @Schema(example = "Juan Pérez") String tecnicoNombre,
        @Schema(example = "0.58") Double distanciaKm,
        @Schema(example = "2") Integer tiempoEstimadoMin,
        @Schema(example = "ESTANDAR") String estrategiaCosto,
        @Schema(example = "5464.00") BigDecimal costoBase,
        @Schema(example = "0.00") BigDecimal recargo,
        @Schema(example = "5464.00") BigDecimal total
) {
}
