/**
 * Librería común commons-geo.
 * <p>
 * Centraliza la lógica geográfica que comparten ms-tecnicos y ms-solicitudes:
 * <ul>
 *   <li>{@link utnfc.isi.back.tpi.geo.Haversine}: distancia en línea recta (cálculo local, RN-9).</li>
 *   <li>{@link utnfc.isi.back.tpi.geo.GeoService}: contrato para obtener una ruta real.</li>
 *   <li>{@link utnfc.isi.back.tpi.geo.OsrmGeoService}: implementación que consulta la API pública de OSRM
 *       con timeouts y, si falla, usa Haversine como respaldo (RN-8).</li>
 * </ul>
 * Se registra sola en cada microservicio gracias a {@link utnfc.isi.back.tpi.geo.GeoAutoConfiguration}.
 */
package utnfc.isi.back.tpi.geo;
