package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Informe de gestión para el administrador: cómo viene la operatoria en un período. */
@Schema(description = "Informe de gestión de solicitudes")
public record InformeResponse(
        @Schema(description = "Cuándo se generó el informe") LocalDateTime generado,
        @Schema(example = "2026-11-01") LocalDate desde,
        @Schema(example = "2026-11-30") LocalDate hasta,
        @Schema(example = "42") long totalSolicitudes,
        @Schema(description = "Cantidad por estado (están todos, con 0 si no hay)") Map<String, Long> porEstado,
        @Schema(description = "AUTOMATICA / MANUAL") Map<String, Long> porModo,
        @Schema(description = "NORMAL / URGENTE") Map<String, Long> porPrioridad,
        @Schema(description = "REPARACION / INSTALACION / MANTENIMIENTO") Map<String, Long> porTipoServicio,
        List<Estrategia> porEstrategia,
        Facturacion facturacion,
        @Schema(example = "1.35") Double distanciaPromedioKm,
        Ofertas ofertas,
        List<Tecnico> porTecnico
) {

    /** Uso de cada estrategia de costo (RN-11). */
    public record Estrategia(@Schema(example = "ESTANDAR") String estrategia, long cantidad,
                             @Schema(description = "Suma de los costos de visita") BigDecimal totalVisitas) {
    }

    /** Lo facturado por las solicitudes FINALIZADAS (visita + presupuesto). */
    public record Facturacion(long finalizadas, BigDecimal visitas, BigDecimal presupuestos, BigDecimal total,
                              @Schema(description = "Total / finalizadas") BigDecimal ticketPromedio) {
    }

    /** Embudo de ofertas (RN-3): cuántas se enviaron y qué pasó con ellas. */
    public record Ofertas(long enviadas, long aceptadas, long rechazadas, long vencidas, long canceladas,
                          @Schema(description = "Aceptadas / enviadas, en %", example = "66.7") Double tasaAceptacion) {
    }

    /** Desempeño de cada técnico en el período. */
    public record Tecnico(Long tecnicoId, String tecnicoNombre, long enEspera, long enCurso, long finalizadas,
                          @Schema(description = "Ofertas que rechazó, dejó vencer o derivó") long descartadas,
                          @Schema(description = "Visitas + presupuestos de sus FINALIZADAS") BigDecimal facturado) {
    }
}
