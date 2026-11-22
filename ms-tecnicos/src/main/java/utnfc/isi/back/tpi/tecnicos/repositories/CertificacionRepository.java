package utnfc.isi.back.tpi.tecnicos.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import utnfc.isi.back.tpi.tecnicos.models.Certificacion;

public interface CertificacionRepository extends JpaRepository<Certificacion, Long> {
}
