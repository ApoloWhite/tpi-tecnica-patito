package utnfc.isi.back.tpi.solicitudes.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Datos del cliente que devuelve MS-Clientes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClienteDto(Long id, String nombre, String apellido, String dni, String calle, String barrio,
                         Double latitud, Double longitud) {
}
