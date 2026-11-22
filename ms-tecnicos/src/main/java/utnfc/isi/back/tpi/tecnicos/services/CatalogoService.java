package utnfc.isi.back.tpi.tecnicos.services;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.tecnicos.dtos.CatalogoResponse;
import utnfc.isi.back.tpi.tecnicos.repositories.CertificacionRepository;
import utnfc.isi.back.tpi.tecnicos.repositories.ZonaCoberturaRepository;

import java.util.List;

/**
 * Catálogos de certificaciones y zonas. Son tablas chicas y fijas (3 y 2 filas),
 * por eso se devuelven completas sin paginar.
 */
@Service
public class CatalogoService {

    private final CertificacionRepository certificacionRepository;
    private final ZonaCoberturaRepository zonaRepository;

    public CatalogoService(CertificacionRepository certificacionRepository, ZonaCoberturaRepository zonaRepository) {
        this.certificacionRepository = certificacionRepository;
        this.zonaRepository = zonaRepository;
    }

    @Transactional(readOnly = true)
    public List<CatalogoResponse> listarCertificaciones() {
        return certificacionRepository.findAll(Sort.by("id")).stream()
                .map(c -> new CatalogoResponse(c.getId(), c.getNombre())).toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogoResponse> listarZonas() {
        return zonaRepository.findAll(Sort.by("id")).stream()
                .map(z -> new CatalogoResponse(z.getId(), z.getNombre())).toList();
    }
}
