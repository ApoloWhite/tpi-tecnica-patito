package utnfc.isi.back.tpi.solicitudes.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
