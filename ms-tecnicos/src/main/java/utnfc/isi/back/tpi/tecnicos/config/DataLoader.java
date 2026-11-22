package utnfc.isi.back.tpi.tecnicos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.tecnicos.models.Certificacion;
import utnfc.isi.back.tpi.tecnicos.models.Tecnico;
import utnfc.isi.back.tpi.tecnicos.models.TecnicoCertificacion;
import utnfc.isi.back.tpi.tecnicos.models.TecnicoZona;
import utnfc.isi.back.tpi.tecnicos.models.ZonaCobertura;
import utnfc.isi.back.tpi.tecnicos.repositories.CertificacionRepository;
import utnfc.isi.back.tpi.tecnicos.repositories.TecnicoCertificacionRepository;
import utnfc.isi.back.tpi.tecnicos.repositories.TecnicoRepository;
import utnfc.isi.back.tpi.tecnicos.repositories.TecnicoZonaRepository;
import utnfc.isi.back.tpi.tecnicos.repositories.ZonaCoberturaRepository;

/**
 * Datos de prueba del PDF: 3 certificaciones, 2 zonas y 3 técnicos.
 * Juan (Redes, Impresoras) en el Centro, Ana (Impresoras, Notebooks Apple) en Nueva Córdoba
 * y Lejano (Redes) en Villa Carlos Paz, a más de 13 km del Centro.
 * Como H2 está en archivo, solo carga si la base está vacía.
 */
@Component
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final TecnicoRepository tecnicoRepository;
    private final CertificacionRepository certificacionRepository;
    private final ZonaCoberturaRepository zonaRepository;
    private final TecnicoCertificacionRepository tecnicoCertificacionRepository;
    private final TecnicoZonaRepository tecnicoZonaRepository;

    public DataLoader(TecnicoRepository tecnicoRepository, CertificacionRepository certificacionRepository,
                      ZonaCoberturaRepository zonaRepository,
                      TecnicoCertificacionRepository tecnicoCertificacionRepository,
                      TecnicoZonaRepository tecnicoZonaRepository) {
        this.tecnicoRepository = tecnicoRepository;
        this.certificacionRepository = certificacionRepository;
        this.zonaRepository = zonaRepository;
        this.tecnicoCertificacionRepository = tecnicoCertificacionRepository;
        this.tecnicoZonaRepository = tecnicoZonaRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (tecnicoRepository.count() > 0) {
            log.info("La base de técnicos ya tiene datos, no se cargan datos de prueba");
            return;
        }
        Certificacion redes = certificacionRepository.save(Certificacion.builder().nombre("Redes").build());
        Certificacion impresoras = certificacionRepository.save(Certificacion.builder().nombre("Impresoras").build());
        Certificacion notebooks = certificacionRepository.save(Certificacion.builder().nombre("Notebooks Apple").build());

        ZonaCobertura centro = zonaRepository.save(ZonaCobertura.builder().nombre("Centro").build());
        ZonaCobertura nuevaCordoba = zonaRepository.save(ZonaCobertura.builder().nombre("Nueva Córdoba").build());

        Tecnico juan = tecnico("T-001", "Juan", "Pérez", "25111222", -31.4167, -64.1833);
        Tecnico ana = tecnico("T-002", "Ana", "Gómez", "26222333", -31.4260, -64.1900);
        Tecnico lejano = tecnico("T-003", "Lejano", "López", "27333444", -31.4241, -64.4978);

        asignar(juan, redes, centro);
        asignar(juan, impresoras, null);
        asignar(ana, impresoras, nuevaCordoba);
        asignar(ana, notebooks, null);
        asignar(lejano, redes, null);

        log.info("Datos de prueba cargados: 3 técnicos, 3 certificaciones, 2 zonas");
    }

    private Tecnico tecnico(String legajo, String nombre, String apellido, String dni, double lat, double lon) {
        return tecnicoRepository.save(Tecnico.builder().legajo(legajo).nombre(nombre).apellido(apellido)
                .dni(dni).latitud(lat).longitud(lon).disponible(true).build());
    }

    private void asignar(Tecnico tecnico, Certificacion certificacion, ZonaCobertura zona) {
        tecnicoCertificacionRepository.save(
                TecnicoCertificacion.builder().tecnico(tecnico).certificacion(certificacion).build());
        if (zona != null) {
            tecnicoZonaRepository.save(TecnicoZona.builder().tecnico(tecnico).zona(zona).build());
        }
    }
}
