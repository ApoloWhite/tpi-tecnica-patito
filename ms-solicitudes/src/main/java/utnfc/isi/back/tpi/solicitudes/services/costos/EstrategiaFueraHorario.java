package utnfc.isi.back.tpi.solicitudes.services.costos;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Prioridad NORMAL fuera de horario, fin de semana o feriado: recargo horario. */
@Component
public class EstrategiaFueraHorario implements EstrategiaCosto {

    @Override
    public String nombre() {
        return "FUERA_HORARIO";
    }

    @Override
    public boolean aplica(ContextoCosto contexto) {
        return !contexto.urgente() && contexto.fueraDeHorario();
    }

    @Override
    public BigDecimal porcentajeRecargo(Tarifas tarifas) {
        return tarifas.recargoFueraHorario();
    }
}
