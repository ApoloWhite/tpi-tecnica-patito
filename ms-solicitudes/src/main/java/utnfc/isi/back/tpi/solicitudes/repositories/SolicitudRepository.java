package utnfc.isi.back.tpi.solicitudes.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud;
import utnfc.isi.back.tpi.solicitudes.models.Solicitud;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface SolicitudRepository extends JpaRepository<Solicitud, Long> {

    Page<Solicitud> findByEstado(EstadoSolicitud estado, Pageable pageable);

    /** Ofertas cuyo plazo de 15 minutos ya venció (RN-3). */
    List<Solicitud> findByEstadoAndOfertaExpiraBefore(EstadoSolicitud estado, LocalDateTime limite);

    /** Cuántas solicitudes tiene un técnico en ciertos estados (RN-5). */
    long countByTecnicoIdAndEstadoIn(Long tecnicoId, Collection<EstadoSolicitud> estados);

    long countByTecnicoIdAndEstado(Long tecnicoId, EstadoSolicitud estado);

    // ------------------------------------------------------------------ Informe (solo lectura, agregados)
    // Todas filtran por fecha de creación entre :desde y :hasta y devuelven filas [clave, cantidad, ...].

    /** Cantidad de solicitudes por estado. */
    @Query("select s.estado, count(s) from Solicitud s where s.fechaCreacion between :desde and :hasta group by s.estado")
    List<Object[]> contarPorEstado(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Cantidad por modo de asignación (null = solicitudes viejas, se cuentan como MANUAL). */
    @Query("select s.modoAsignacion, count(s) from Solicitud s where s.fechaCreacion between :desde and :hasta group by s.modoAsignacion")
    List<Object[]> contarPorModo(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Cantidad por prioridad. */
    @Query("select s.prioridad, count(s) from Solicitud s where s.fechaCreacion between :desde and :hasta group by s.prioridad")
    List<Object[]> contarPorPrioridad(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Cantidad por tipo de servicio (una solicitud con 2 tipos cuenta en los 2). */
    @Query("select t, count(s) from Solicitud s join s.tiposServicio t where s.fechaCreacion between :desde and :hasta group by t")
    List<Object[]> contarPorTipoServicio(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Por estrategia de costo: cantidad y suma de los costos de visita (solo las que ya tienen costo). */
    @Query("select s.estrategiaCosto, count(s), coalesce(sum(s.total), 0) from Solicitud s "
            + "where s.estrategiaCosto is not null and s.fechaCreacion between :desde and :hasta group by s.estrategiaCosto")
    List<Object[]> resumirPorEstrategia(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Facturación de las FINALIZADAS: [cantidad, suma visitas, suma presupuestos]. */
    @Query("select count(s), coalesce(sum(s.total), 0), coalesce(sum(s.presupuesto), 0) from Solicitud s "
            + "where s.estado = utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.FINALIZADA "
            + "and s.fechaCreacion between :desde and :hasta")
    List<Object[]> resumirFinalizadas(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Distancia promedio de las visitas con costo calculado. */
    @Query("select avg(s.distanciaKm) from Solicitud s where s.distanciaKm is not null and s.fechaCreacion between :desde and :hasta")
    Double distanciaPromedio(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Por técnico y estado: [tecnicoId, tecnicoNombre, estado, cantidad, suma visitas, suma presupuestos]. */
    @Query("select s.tecnicoId, s.tecnicoNombre, s.estado, count(s), coalesce(sum(s.total), 0), coalesce(sum(s.presupuesto), 0) "
            + "from Solicitud s where s.tecnicoId is not null and s.fechaCreacion between :desde and :hasta "
            + "group by s.tecnicoId, s.tecnicoNombre, s.estado")
    List<Object[]> resumirPorTecnico(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Cuántas solicitudes "dejó" cada técnico (rechazó, dejó vencer o derivó). */
    @Query("select d, count(s) from Solicitud s join s.tecnicosDescartados d where s.fechaCreacion between :desde and :hasta group by d")
    List<Object[]> contarDescartesPorTecnico(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
