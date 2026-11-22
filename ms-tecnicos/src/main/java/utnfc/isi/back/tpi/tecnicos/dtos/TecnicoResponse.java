package utnfc.isi.back.tpi.tecnicos.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Perfil de un técnico con disponibilidad, certificaciones y zonas. */
@Schema(description = "Técnico registrado")
public record TecnicoResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "T-001") String legajo,
        @Schema(example = "Juan") String nombre,
        @Schema(example = "Pérez") String apellido,
        @Schema(example = "25111222") String dni,
        @Schema(example = "-31.4167") Double latitud,
        @Schema(example = "-64.1833") Double longitud,
        @Schema(example = "true") Boolean disponible,
        List<CatalogoResponse> certificaciones,
        List<CatalogoResponse> zonas
) {
}
