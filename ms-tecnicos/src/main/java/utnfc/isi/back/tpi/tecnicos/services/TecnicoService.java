package utnfc.isi.back.tpi.tecnicos.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.geo.Haversine;
import utnfc.isi.back.tpi.tecnicos.dtos.CandidatoResponse;
import utnfc.isi.back.tpi.tecnicos.dtos.CatalogoResponse;
import utnfc.isi.back.tpi.tecnicos.dtos.TecnicoRequest;
import utnfc.isi.back.tpi.tecnicos.dtos.TecnicoResponse;
import utnfc.isi.back.tpi.tecnicos.exceptions.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.tecnicos.exceptions.ReglaNegocioException;
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

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Lógica de negocio del padrón de técnicos (F3) y de la búsqueda de candidatos (F5, RN-2, RN-9). */
@Service
public class TecnicoService {

    private static final Logger log = LoggerFactory.getLogger(TecnicoService.class);

    private final TecnicoRepository tecnicoRepository;
    private final CertificacionRepository certificacionRepository;
    private final ZonaCoberturaRepository zonaRepository;
    private final TecnicoCertificacionRepository tecnicoCertificacionRepository;
    private final TecnicoZonaRepository tecnicoZonaRepository;
    private final double radioKm;

    public TecnicoService(TecnicoRepository tecnicoRepository,
                          CertificacionRepository certificacionRepository,
                          ZonaCoberturaRepository zonaRepository,
                          TecnicoCertificacionRepository tecnicoCertificacionRepository,
                          TecnicoZonaRepository tecnicoZonaRepository,
                          @Value("${app.candidatos.radio-km:13.0}") double radioKm) {
        this.tecnicoRepository = tecnicoRepository;
        this.certificacionRepository = certificacionRepository;
        this.zonaRepository = zonaRepository;
        this.tecnicoCertificacionRepository = tecnicoCertificacionRepository;
        this.tecnicoZonaRepository = tecnicoZonaRepository;
        this.radioKm = radioKm;
    }

    @Transactional
    public TecnicoResponse crear(TecnicoRequest request) {
        if (tecnicoRepository.existsByDni(request.dni())) {
            throw new ReglaNegocioException("Ya existe un técnico con DNI " + request.dni());
        }
        if (tecnicoRepository.existsByLegajo(request.legajo())) {
            throw new ReglaNegocioException("Ya existe un técnico con legajo " + request.legajo());
        }
        List<Certificacion> certificaciones = buscarCertificaciones(request.certificacionIds());
        List<ZonaCobertura> zonas = buscarZonas(request.zonaIds() == null ? List.of() : request.zonaIds());

        Tecnico tecnico = tecnicoRepository.save(Tecnico.builder()
                .legajo(request.legajo())
                .nombre(request.nombre())
                .apellido(request.apellido())
                .dni(request.dni())
                .latitud(request.latitud())
                .longitud(request.longitud())
                .disponible(request.disponible() == null || request.disponible())
                .build());
        certificaciones.forEach(c -> tecnicoCertificacionRepository.save(
                TecnicoCertificacion.builder().tecnico(tecnico).certificacion(c).build()));
        zonas.forEach(z -> tecnicoZonaRepository.save(
                TecnicoZona.builder().tecnico(tecnico).zona(z).build()));

        log.info("Técnico creado id={} legajo={} con {} certificaciones", tecnico.getId(), tecnico.getLegajo(),
                certificaciones.size());
        return aResponse(tecnico,
                certificaciones.stream().map(c -> new CatalogoResponse(c.getId(), c.getNombre())).toList(),
                zonas.stream().map(z -> new CatalogoResponse(z.getId(), z.getNombre())).toList());
    }

    @Transactional(readOnly = true)
    public Page<TecnicoResponse> listar(Boolean disponible, Pageable pageable) {
        Page<Tecnico> pagina = disponible == null
                ? tecnicoRepository.findAll(pageable)
                : tecnicoRepository.findByDisponible(disponible, pageable);
        List<Long> ids = pagina.getContent().stream().map(Tecnico::getId).toList();
        Map<Long, List<CatalogoResponse>> certificaciones = certificacionesPorTecnico(ids);
        Map<Long, List<CatalogoResponse>> zonas = zonasPorTecnico(ids);
        return pagina.map(t -> aResponse(t,
                certificaciones.getOrDefault(t.getId(), List.of()),
                zonas.getOrDefault(t.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public TecnicoResponse obtener(Long id) {
        Tecnico tecnico = buscar(id);
        List<Long> ids = List.of(id);
        return aResponse(tecnico,
                certificacionesPorTecnico(ids).getOrDefault(id, List.of()),
                zonasPorTecnico(ids).getOrDefault(id, List.of()));
    }

    @Transactional
    public TecnicoResponse cambiarDisponibilidad(Long id, boolean disponible) {
        Tecnico tecnico = buscar(id);
        tecnico.setDisponible(disponible);
        log.info("Técnico id={} ahora disponible={}", id, disponible);
        return obtener(id);
    }

    /**
     * Candidatos para una solicitud: técnicos disponibles, con la certificación pedida
     * y a menos de {@code app.candidatos.radio-km} del cliente (Haversine local, sin API externa).
     * Ordenados del más cercano al más lejano.
     */
    @Transactional(readOnly = true)
    public List<CandidatoResponse> buscarCandidatos(Long certificacionId, double latitud, double longitud) {
        if (!certificacionRepository.existsById(certificacionId)) {
            throw new RecursoNoEncontradoException("No existe la certificación con id " + certificacionId);
        }
        List<CandidatoResponse> candidatos = tecnicoCertificacionRepository
                .findTecnicosDisponiblesConCertificacion(certificacionId).stream()
                .map(t -> new CandidatoResponse(t.getId(), t.getNombre(), t.getApellido(),
                        t.getLatitud(), t.getLongitud(),
                        Haversine.distanciaKm(latitud, longitud, t.getLatitud(), t.getLongitud())))
                .filter(c -> c.distanciaKm() < radioKm)
                .sorted(Comparator.comparing(CandidatoResponse::distanciaKm))
                .toList();
        log.info("Búsqueda de candidatos certificacion={} -> {} técnicos a menos de {} km",
                certificacionId, candidatos.size(), radioKm);
        return candidatos;
    }

    private Tecnico buscar(Long id) {
        return tecnicoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el técnico con id " + id));
    }

    private List<Certificacion> buscarCertificaciones(Collection<Long> ids) {
        Collection<Long> unicos = new LinkedHashSet<>(ids);
        List<Certificacion> encontradas = certificacionRepository.findAllById(unicos);
        if (encontradas.size() != unicos.size()) {
            throw new ReglaNegocioException("Alguna certificación no existe: " + faltantes(unicos,
                    encontradas.stream().map(Certificacion::getId).toList()));
        }
        return encontradas;
    }

    private List<ZonaCobertura> buscarZonas(Collection<Long> ids) {
        Collection<Long> unicos = new LinkedHashSet<>(ids);
        List<ZonaCobertura> encontradas = zonaRepository.findAllById(unicos);
        if (encontradas.size() != unicos.size()) {
            throw new ReglaNegocioException("Alguna zona no existe: " + faltantes(unicos,
                    encontradas.stream().map(ZonaCobertura::getId).toList()));
        }
        return encontradas;
    }

    private static Collection<Long> faltantes(Collection<Long> pedidos, Collection<Long> encontrados) {
        Collection<Long> resultado = new HashSet<>(pedidos);
        resultado.removeAll(encontrados);
        return resultado;
    }

    private Map<Long, List<CatalogoResponse>> certificacionesPorTecnico(Collection<Long> tecnicoIds) {
        if (tecnicoIds.isEmpty()) {
            return Map.of();
        }
        return tecnicoCertificacionRepository.findConCertificacionByTecnicoIdIn(tecnicoIds).stream()
                .collect(Collectors.groupingBy(tc -> tc.getTecnico().getId(),
                        Collectors.mapping(tc -> new CatalogoResponse(tc.getCertificacion().getId(),
                                tc.getCertificacion().getNombre()), Collectors.toList())));
    }

    private Map<Long, List<CatalogoResponse>> zonasPorTecnico(Collection<Long> tecnicoIds) {
        if (tecnicoIds.isEmpty()) {
            return Map.of();
        }
        return tecnicoZonaRepository.findConZonaByTecnicoIdIn(tecnicoIds).stream()
                .collect(Collectors.groupingBy(tz -> tz.getTecnico().getId(),
                        Collectors.mapping(tz -> new CatalogoResponse(tz.getZona().getId(),
                                tz.getZona().getNombre()), Collectors.toList())));
    }

    private static TecnicoResponse aResponse(Tecnico t, List<CatalogoResponse> certificaciones,
                                             List<CatalogoResponse> zonas) {
        return new TecnicoResponse(t.getId(), t.getLegajo(), t.getNombre(), t.getApellido(), t.getDni(),
                t.getLatitud(), t.getLongitud(), t.getDisponible(), certificaciones, zonas);
    }
}
