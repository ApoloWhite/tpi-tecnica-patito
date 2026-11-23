package utnfc.isi.back.tpi.solicitudes.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import utnfc.isi.back.tpi.solicitudes.models.HistorialEstado;

import java.time.LocalDateTime;
import java.util.List;

public interface HistorialEstadoRepository extends JpaRepository<HistorialEstado, Long> {

    List<HistorialEstado> findBySolicitudIdOrderByFechaHoraAscIdAsc(Long solicitudId);

    /**
     * Transiciones agrupadas: [estadoAnterior, estadoNuevo, usuario, cantidad]. Sirve para el embudo de ofertas:
     * OFERTADA -> ASIGNADA es una aceptación; OFERTADA -> PENDIENTE hecha por "sistema" es un vencimiento
     * y hecha por un técnico es un rechazo.
     */
    @Query("select h.estadoAnterior, h.estadoNuevo, h.usuario, count(h) from HistorialEstado h "
            + "where h.solicitud.fechaCreacion between :desde and :hasta "
            + "group by h.estadoAnterior, h.estadoNuevo, h.usuario")
    List<Object[]> contarTransiciones(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
