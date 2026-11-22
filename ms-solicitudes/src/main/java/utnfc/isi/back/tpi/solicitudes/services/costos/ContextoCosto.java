package utnfc.isi.back.tpi.solicitudes.services.costos;

/** Datos que necesita una estrategia para calcular el costo de visita. */
public record ContextoCosto(double distanciaKm, boolean urgente, boolean fueraDeHorario) {
}
