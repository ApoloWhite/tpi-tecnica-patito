package utnfc.isi.back.tpi.solicitudes.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Datos del técnico que devuelve MS-Técnicos. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TecnicoDto(Long id, String legajo, String nombre, String apellido, Double latitud, Double longitud,
                         Boolean disponible) {
}
