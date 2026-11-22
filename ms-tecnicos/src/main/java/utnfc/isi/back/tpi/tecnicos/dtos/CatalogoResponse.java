package utnfc.isi.back.tpi.tecnicos.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

/** Elemento de un catálogo simple (certificación o zona). */
@Schema(description = "Elemento de catálogo")
public record CatalogoResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Redes") String nombre
) {
}
