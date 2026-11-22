package utnfc.isi.back.tpi.solicitudes.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Candidato que devuelve MS-Técnicos en GET /api/v1/tecnicos/candidatos. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CandidatoDto(Long tecnicoId, String nombre, String apellido, Double latitud, Double longitud,
                           Double distanciaKm) {
}
