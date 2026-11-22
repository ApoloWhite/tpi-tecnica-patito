package utnfc.isi.back.tpi.clientes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.clientes.models.Cliente;
import utnfc.isi.back.tpi.clientes.repositories.ClienteRepository;

import java.util.List;

/**
 * Carga los clientes de prueba del PDF. Como la base H2 está en archivo,
 * solo inserta si la tabla está vacía (no duplica al reiniciar).
 */
@Component
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final ClienteRepository clienteRepository;

    public DataLoader(ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (clienteRepository.count() > 0) {
            log.info("La base de clientes ya tiene datos, no se cargan datos de prueba");
            return;
        }
        clienteRepository.saveAll(List.of(
                Cliente.builder().nombre("Carlos").apellido("García").dni("30111222")
                        .calle("Av. Colón 150").barrio("Centro").latitud(-31.420).longitud(-64.188).build(),
                Cliente.builder().nombre("María").apellido("Rodríguez").dni("31222333")
                        .calle("Obispo Trejo 900").barrio("Nueva Córdoba").latitud(-31.4280).longitud(-64.1870).build(),
                Cliente.builder().nombre("Lucía").apellido("Fernández").dni("32333444")
                        .calle("Av. Rafael Núñez 4500").barrio("Cerro de las Rosas").latitud(-31.3700).longitud(-64.2350).build()
        ));
        log.info("Datos de prueba cargados: 3 clientes");
    }
}
