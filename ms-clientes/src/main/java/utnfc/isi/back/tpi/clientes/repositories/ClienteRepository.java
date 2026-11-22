package utnfc.isi.back.tpi.clientes.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import utnfc.isi.back.tpi.clientes.models.Cliente;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    boolean existsByDni(String dni);

    boolean existsByDniAndIdNot(String dni, Long id);

    Page<Cliente> findByApellidoContainingIgnoreCase(String apellido, Pageable pageable);
}
