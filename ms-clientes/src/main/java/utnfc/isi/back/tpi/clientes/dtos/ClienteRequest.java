package utnfc.isi.back.tpi.clientes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Datos para registrar o modificar un cliente. */
@Schema(description = "Datos de entrada de un cliente")
public record ClienteRequest(
        @Schema(example = "Pedro") @NotBlank(message = "El nombre es obligatorio") @Size(max = 80)
        String nombre,

        @Schema(example = "Martínez") @NotBlank(message = "El apellido es obligatorio") @Size(max = 80)
        String apellido,

        @Schema(example = "40111222", description = "Solo números, 7 u 8 dígitos")
        @NotBlank(message = "El DNI es obligatorio")
        @Pattern(regexp = "\\d{7,8}", message = "El DNI debe tener 7 u 8 dígitos")
        String dni,

        @Schema(example = "Av. Colón 1200") @NotBlank(message = "La calle es obligatoria") @Size(max = 120)
        String calle,

        @Schema(example = "Alberdi") @Size(max = 80)
        String barrio,

        @Schema(example = "-31.4135") @NotNull(message = "La latitud es obligatoria")
        @DecimalMin(value = "-90.0", message = "La latitud debe estar entre -90 y 90")
        @DecimalMax(value = "90.0", message = "La latitud debe estar entre -90 y 90")
        Double latitud,

        @Schema(example = "-64.2010") @NotNull(message = "La longitud es obligatoria")
        @DecimalMin(value = "-180.0", message = "La longitud debe estar entre -180 y 180")
        @DecimalMax(value = "180.0", message = "La longitud debe estar entre -180 y 180")
        Double longitud
) {
}
