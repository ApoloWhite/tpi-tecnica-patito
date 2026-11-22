package utnfc.isi.back.tpi.clientes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import utnfc.isi.back.tpi.clientes.models.Cliente;

/** Datos de un cliente que devuelve la API (no se expone la entidad JPA). */
@Schema(description = "Cliente registrado")
public record ClienteResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Carlos") String nombre,
        @Schema(example = "García") String apellido,
        @Schema(example = "30111222") String dni,
        @Schema(example = "Av. Colón 1200") String calle,
        @Schema(example = "Centro") String barrio,
        @Schema(example = "-31.420") Double latitud,
        @Schema(example = "-64.188") Double longitud
) {
    public static ClienteResponse desde(Cliente c) {
        return new ClienteResponse(c.getId(), c.getNombre(), c.getApellido(), c.getDni(),
                c.getCalle(), c.getBarrio(), c.getLatitud(), c.getLongitud());
    }
}
