package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Diagnóstico y presupuesto que carga el técnico después de revisar el equipo (F7). */
@Schema(description = "Diagnóstico y presupuesto del técnico")
public record PresupuestoRequest(
        @Schema(example = "Rodillo de arrastre gastado, hay que reemplazarlo")
        @NotBlank(message = "El diagnóstico es obligatorio") @Size(max = 500)
        String diagnostico,

        @Schema(example = "1500.00")
        @NotNull(message = "El importe es obligatorio")
        @Positive(message = "El importe debe ser mayor a cero")
        @Digits(integer = 10, fraction = 2, message = "El importe admite hasta 2 decimales")
        BigDecimal importe
) {
}
