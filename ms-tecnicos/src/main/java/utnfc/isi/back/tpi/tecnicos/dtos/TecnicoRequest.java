package utnfc.isi.back.tpi.tecnicos.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Datos para registrar un técnico con sus certificaciones y zonas de cobertura. */
@Schema(description = "Datos de entrada de un técnico")
public record TecnicoRequest(
        @Schema(example = "T-010") @NotBlank(message = "El legajo es obligatorio") @Size(max = 20)
        String legajo,

        @Schema(example = "Laura") @NotBlank(message = "El nombre es obligatorio") @Size(max = 80)
        String nombre,

        @Schema(example = "Sosa") @NotBlank(message = "El apellido es obligatorio") @Size(max = 80)
        String apellido,

        @Schema(example = "28999111") @NotBlank(message = "El DNI es obligatorio")
        @Pattern(regexp = "\\d{7,8}", message = "El DNI debe tener 7 u 8 dígitos")
        String dni,

        @Schema(example = "-31.4100") @NotNull(message = "La latitud es obligatoria")
        @DecimalMin(value = "-90.0", message = "La latitud debe estar entre -90 y 90")
        @DecimalMax(value = "90.0", message = "La latitud debe estar entre -90 y 90")
        Double latitud,

        @Schema(example = "-64.1800") @NotNull(message = "La longitud es obligatoria")
        @DecimalMin(value = "-180.0", message = "La longitud debe estar entre -180 y 180")
        @DecimalMax(value = "180.0", message = "La longitud debe estar entre -180 y 180")
        Double longitud,

        @Schema(example = "true", description = "Si se omite, el técnico queda disponible")
        Boolean disponible,

        @Schema(example = "[1, 2]") @NotEmpty(message = "Debe tener al menos una certificación")
        List<@NotNull Long> certificacionIds,

        @Schema(example = "[1]")
        List<@NotNull Long> zonaIds
) {
}
