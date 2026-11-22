package utnfc.isi.back.tpi.solicitudes.services.costos;

import org.springframework.stereotype.Service;
import utnfc.isi.back.tpi.solicitudes.models.Prioridad;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Elige la estrategia de costo que corresponde y la aplica (RN-8, RN-11).
 * Spring inyecta todas las implementaciones de {@link EstrategiaCosto}: para agregar
 * una estrategia nueva alcanza con crear otra clase @Component.
 */
@Service
public class CalculadoraCosto {

    private final List<EstrategiaCosto> estrategias;
    private final Tarifas tarifas;

    public CalculadoraCosto(List<EstrategiaCosto> estrategias, Tarifas tarifas) {
        this.estrategias = estrategias;
        this.tarifas = tarifas;
    }

    public DetalleCosto calcular(double distanciaKm, Prioridad prioridad, LocalDateTime fechaHora, boolean esFeriado) {
        ContextoCosto contexto = new ContextoCosto(distanciaKm, prioridad == Prioridad.URGENTE,
                esFueraDeHorario(fechaHora, esFeriado));
        EstrategiaCosto estrategia = estrategias.stream()
                .filter(e -> e.aplica(contexto))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay estrategia de costo para " + contexto));
        return estrategia.calcular(contexto, tarifas);
    }

    /** Fuera de horario = feriado, sábado, domingo o fuera de la franja horaInicio-horaFin. */
    boolean esFueraDeHorario(LocalDateTime fechaHora, boolean esFeriado) {
        DayOfWeek dia = fechaHora.getDayOfWeek();
        int hora = fechaHora.getHour();
        return esFeriado || dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY
                || hora < tarifas.horaInicio() || hora >= tarifas.horaFin();
    }
}
