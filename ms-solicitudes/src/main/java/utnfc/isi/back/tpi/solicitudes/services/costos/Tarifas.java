package utnfc.isi.back.tpi.solicitudes.services.costos;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Tarifas del costo de visita, leídas de application.yml (prefijo "tarifas").
 *
 * @param montoFijo          monto fijo por visita
 * @param porKm              adicional por kilómetro recorrido
 * @param recargoPrioridad   porcentaje extra para prioridad URGENTE (0.30 = 30 %)
 * @param recargoFueraHorario porcentaje extra fuera del horario hábil o en feriado
 * @param horaInicio         inicio del horario hábil (inclusive)
 * @param horaFin            fin del horario hábil (exclusive)
 */
@ConfigurationProperties(prefix = "tarifas")
public record Tarifas(BigDecimal montoFijo, BigDecimal porKm, BigDecimal recargoPrioridad,
                      BigDecimal recargoFueraHorario, int horaInicio, int horaFin) {
}
