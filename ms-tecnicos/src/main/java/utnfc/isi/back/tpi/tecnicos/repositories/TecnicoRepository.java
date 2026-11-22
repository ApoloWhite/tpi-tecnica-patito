package utnfc.isi.back.tpi.tecnicos.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import utnfc.isi.back.tpi.tecnicos.models.Tecnico;

public interface TecnicoRepository extends JpaRepository<Tecnico, Long> {

    boolean existsByDni(String dni);

    boolean existsByLegajo(String legajo);

    Page<Tecnico> findByDisponible(Boolean disponible, Pageable pageable);
}
