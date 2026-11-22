package utnfc.isi.back.tpi.geo;

/**
 * Fórmula de Haversine: distancia en línea recta sobre la superficie de la Tierra.
 * Es un cálculo local (no llama a ninguna API), por eso se usa para filtrar candidatos (RN-9)
 * y como respaldo cuando la API de mapas no responde (RN-8).
 */
public final class Haversine {

    private static final double RADIO_TIERRA_KM = 6371.0;
    /** Velocidad promedio urbana usada para estimar minutos cuando no hay ruta real. */
    private static final double VELOCIDAD_PROMEDIO_KMH = 30.0;

    private Haversine() {
    }

    public static double distanciaKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return redondear(RADIO_TIERRA_KM * c);
    }

    public static int minutosEstimados(double km) {
        return (int) Math.ceil(km / VELOCIDAD_PROMEDIO_KMH * 60);
    }

    public static Ruta ruta(double lat1, double lon1, double lat2, double lon2) {
        double km = distanciaKm(lat1, lon1, lat2, lon2);
        return new Ruta(km, minutosEstimados(km), Ruta.Fuente.HAVERSINE);
    }

    public static double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
