package utnfc.isi.back.tpi.solicitudes.models;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados de una solicitud y transiciones permitidas (RN-7).
 * <pre>
 * PENDIENTE -> OFERTADA -> ASIGNADA -> EN_CURSO -> FINALIZADA
 * OFERTADA -> PENDIENTE (rechazo o vencimiento), ASIGNADA -> CANCELADA (rechazo de presupuesto)
 * EN_CURSO <-> REPROGRAMADA (falta de repuesto), EN_CURSO -> DERIVADA -> PENDIENTE
 * </pre>
 */
public enum EstadoSolicitud {
    PENDIENTE, OFERTADA, ASIGNADA, EN_CURSO, REPROGRAMADA, DERIVADA, CANCELADA, FINALIZADA;

    /** Estados que ocupan un lugar "en espera" en la agenda del técnico (RN-5). */
    public static final Set<EstadoSolicitud> EN_ESPERA = EnumSet.of(OFERTADA, ASIGNADA, REPROGRAMADA);

    private static final Map<EstadoSolicitud, Set<EstadoSolicitud>> TRANSICIONES = new EnumMap<>(EstadoSolicitud.class);

    static {
        TRANSICIONES.put(PENDIENTE, EnumSet.of(OFERTADA, CANCELADA));
        TRANSICIONES.put(OFERTADA, EnumSet.of(ASIGNADA, PENDIENTE, CANCELADA));
        TRANSICIONES.put(ASIGNADA, EnumSet.of(EN_CURSO, CANCELADA));
        TRANSICIONES.put(EN_CURSO, EnumSet.of(FINALIZADA, REPROGRAMADA, DERIVADA));
        TRANSICIONES.put(REPROGRAMADA, EnumSet.of(EN_CURSO));
        TRANSICIONES.put(DERIVADA, EnumSet.of(PENDIENTE));
        TRANSICIONES.put(CANCELADA, EnumSet.noneOf(EstadoSolicitud.class));
        TRANSICIONES.put(FINALIZADA, EnumSet.noneOf(EstadoSolicitud.class));
    }

    public boolean puedePasarA(EstadoSolicitud destino) {
        return TRANSICIONES.get(this).contains(destino);
    }
}
