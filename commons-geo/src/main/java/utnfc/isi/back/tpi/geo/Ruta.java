package utnfc.isi.back.tpi.geo;

/**
 * Resultado de calcular un recorrido entre dos puntos.
 *
 * @param kilometros distancia del recorrido en km
 * @param minutos    tiempo estimado de viaje en minutos
 * @param fuente     de dónde salió el dato: OSRM (ruta real) o HAVERSINE (línea recta, respaldo)
 */
public record Ruta(double kilometros, int minutos, Fuente fuente) {

    public enum Fuente { OSRM, HAVERSINE }
}
