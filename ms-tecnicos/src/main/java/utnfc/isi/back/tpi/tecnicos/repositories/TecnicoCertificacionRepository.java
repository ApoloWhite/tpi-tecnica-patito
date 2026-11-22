package utnfc.isi.back.tpi.tecnicos.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import utnfc.isi.back.tpi.tecnicos.models.Tecnico;
import utnfc.isi.back.tpi.tecnicos.models.TecnicoCertificacion;

import java.util.Collection;
import java.util.List;

public interface TecnicoCertificacionRepository extends JpaRepository<TecnicoCertificacion, Long> {

    /** Certificaciones de varios técnicos en una sola consulta (evita el problema N+1 al listar). */
    @Query("select tc from TecnicoCertificacion tc join fetch tc.certificacion where tc.tecnico.id in :tecnicoIds")
    List<TecnicoCertificacion> findConCertificacionByTecnicoIdIn(@Param("tecnicoIds") Collection<Long> tecnicoIds);

    /** Técnicos disponibles que tienen una certificación dada (primer filtro de RN-2). */
    @Query("select tc.tecnico from TecnicoCertificacion tc "
            + "where tc.certificacion.id = :certificacionId and tc.tecnico.disponible = true")
    List<Tecnico> findTecnicosDisponiblesConCertificacion(@Param("certificacionId") Long certificacionId);
}
