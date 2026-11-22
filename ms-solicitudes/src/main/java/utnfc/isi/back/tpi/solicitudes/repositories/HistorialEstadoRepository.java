package utnfc.isi.back.tpi.solicitudes.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import utnfc.isi.back.tpi.solicitudes.models.HistorialEstado;

import java.util.List;

public interface HistorialEstadoRepository extends JpaRepository<HistorialEstado, Long> {

    List<HistorialEstado> findBySolicitudIdOrderByFechaHoraAscIdAsc(Long solicitudId);
}
