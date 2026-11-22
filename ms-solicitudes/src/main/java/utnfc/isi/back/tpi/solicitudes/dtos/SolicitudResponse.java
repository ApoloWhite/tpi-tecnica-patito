package utnfc.isi.back.tpi.solicitudes.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud;
import utnfc.isi.back.tpi.solicitudes.models.ModoAsignacion;
import utnfc.isi.back.tpi.solicitudes.models.Prioridad;
import utnfc.isi.back.tpi.solicitudes.models.Solicitud;
import utnfc.isi.back.tpi.solicitudes.models.TipoServicio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Detalle de una solicitud con su estado, el snapshot de cliente/técnico y el costo calculado. */
@Schema(description = "Solicitud de servicio")
public record SolicitudResponse(
        Long id,
        Long clienteId,
        @Schema(example = "Carlos García") String clienteNombre,
        @Schema(example = "Av. Colón 150, Centro") String domicilio,
        Double latitud,
        Double longitud,
        List<TipoServicio> tiposServicio,
        String marca,
        String modelo,
        String descripcion,
        Long especialidadId,
        Prioridad prioridad,
        LocalDateTime fechaHoraProgramada,
        Boolean esFeriado,
        EstadoSolicitud estado,
        @Schema(example = "AUTOMATICA") ModoAsignacion modoAsignacion,
        @Schema(description = "Técnicos que rechazaron o dejaron vencer la oferta") List<Long> tecnicosDescartados,
        Long tecnicoId,
        String tecnicoNombre,
        LocalDateTime ofertaExpira,
        Double distanciaKm,
        Integer tiempoEstimadoMin,
        @Schema(example = "OSRM", description = "OSRM = ruta real; HAVERSINE = línea recta (respaldo)") String fuenteDistancia,
        @Schema(example = "ESTANDAR") String estrategiaCosto,
        BigDecimal costoBase,
        BigDecimal recargo,
        BigDecimal total,
        String diagnostico,
        BigDecimal presupuesto,
        LocalDateTime fechaCreacion
) {
    public static SolicitudResponse desde(Solicitud s) {
        return new SolicitudResponse(s.getId(), s.getClienteId(), s.getClienteNombre(), s.getDomicilio(),
                s.getLatitud(), s.getLongitud(), s.getTiposServicio().stream().sorted().toList(),
                s.getMarca(), s.getModelo(), s.getDescripcion(), s.getEspecialidadId(), s.getPrioridad(),
                s.getFechaHoraProgramada(), s.getEsFeriado(), s.getEstado(),
                s.getModoAsignacion() == null ? ModoAsignacion.MANUAL : s.getModoAsignacion(),
                s.getTecnicosDescartados().stream().sorted().toList(), s.getTecnicoId(), s.getTecnicoNombre(),
                s.getOfertaExpira(), s.getDistanciaKm(), s.getTiempoEstimadoMin(), s.getFuenteDistancia(),
                s.getEstrategiaCosto(), s.getCostoBase(), s.getRecargo(), s.getTotal(), s.getDiagnostico(),
                s.getPresupuesto(), s.getFechaCreacion());
    }
}
