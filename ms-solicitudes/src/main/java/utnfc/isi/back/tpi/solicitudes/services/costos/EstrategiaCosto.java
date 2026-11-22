package utnfc.isi.back.tpi.solicitudes.services.costos;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Patrón Strategy para el costo de visita (RN-8, RN-11).
 * Todas las estrategias parten del mismo costo base (monto fijo + km x tarifa)
 * y difieren en el recargo que aplican.
 */
public interface EstrategiaCosto {

    /** Nombre que se guarda en la solicitud (ESTANDAR, PRIORIDAD, FUERA_HORARIO, COMBINADA). */
    String nombre();

    /** Indica si esta estrategia corresponde para el contexto dado. */
    boolean aplica(ContextoCosto contexto);

    /** Porcentaje de recargo sobre el costo base (0 = sin recargo). */
    BigDecimal porcentajeRecargo(Tarifas tarifas);

    default DetalleCosto calcular(ContextoCosto contexto, Tarifas tarifas) {
        BigDecimal base = tarifas.montoFijo()
                .add(tarifas.porKm().multiply(BigDecimal.valueOf(contexto.distanciaKm())))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal recargo = base.multiply(porcentajeRecargo(tarifas)).setScale(2, RoundingMode.HALF_UP);
        return new DetalleCosto(nombre(), base, recargo, base.add(recargo));
    }
}
