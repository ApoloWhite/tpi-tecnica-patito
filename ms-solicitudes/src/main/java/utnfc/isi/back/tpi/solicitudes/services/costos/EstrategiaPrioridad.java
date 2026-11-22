package utnfc.isi.back.tpi.solicitudes.services.costos;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Prioridad URGENTE en horario hábil: recargo por prioridad. */
@Component
public class EstrategiaPrioridad implements EstrategiaCosto {

    @Override
    public String nombre() {
        return "PRIORIDAD";
    }

    @Override
    public boolean aplica(ContextoCosto contexto) {
        return contexto.urgente() && !contexto.fueraDeHorario();
    }

    @Override
    public BigDecimal porcentajeRecargo(Tarifas tarifas) {
        return tarifas.recargoPrioridad();
    }
}
