package utnfc.isi.back.tpi.geo;

/** Contrato para calcular rutas entre dos coordenadas (latitud, longitud en grados). */
public interface GeoService {

    Ruta calcularRuta(double latOrigen, double lonOrigen, double latDestino, double lonDestino);
}
