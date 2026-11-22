package utnfc.isi.back.tpi.solicitudes.services.costos;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Prioridad NORMAL en horario hábil: solo monto fijo + km. */
@Component
public class EstrategiaEstandar implements EstrategiaCosto {

    @Override
    public String nombre() {
        return "ESTANDAR";
    }

    @Override
    public boolean aplica(ContextoCosto contexto) {
        return !contexto.urgente() && !contexto.fueraDeHorario();
    }

    @Override
    public BigDecimal porcentajeRecargo(Tarifas tarifas) {
        return BigDecimal.ZERO;
    }
}
