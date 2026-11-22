package utnfc.isi.back.tpi.tecnicos.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import utnfc.isi.back.tpi.tecnicos.models.TecnicoZona;

import java.util.Collection;
import java.util.List;

public interface TecnicoZonaRepository extends JpaRepository<TecnicoZona, Long> {

    /** Zonas de varios técnicos en una sola consulta (evita el problema N+1 al listar). */
    @Query("select tz from TecnicoZona tz join fetch tz.zona where tz.tecnico.id in :tecnicoIds")
    List<TecnicoZona> findConZonaByTecnicoIdIn(@Param("tecnicoIds") Collection<Long> tecnicoIds);
}
