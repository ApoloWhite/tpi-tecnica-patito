package utnfc.isi.back.tpi.solicitudes.services.costos;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** URGENTE y además fuera de horario: suma ambos recargos. */
@Component
public class EstrategiaCombinada implements EstrategiaCosto {

    @Override
    public String nombre() {
        return "COMBINADA";
    }

    @Override
    public boolean aplica(ContextoCosto contexto) {
        return contexto.urgente() && contexto.fueraDeHorario();
    }

    @Override
    public BigDecimal porcentajeRecargo(Tarifas tarifas) {
        return tarifas.recargoPrioridad().add(tarifas.recargoFueraHorario());
    }
}
