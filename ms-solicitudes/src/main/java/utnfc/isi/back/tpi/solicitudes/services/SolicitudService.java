package utnfc.isi.back.tpi.solicitudes.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.geo.GeoService;
import utnfc.isi.back.tpi.geo.Haversine;
import utnfc.isi.back.tpi.geo.Ruta;
import utnfc.isi.back.tpi.solicitudes.clients.ClientesClient;
import utnfc.isi.back.tpi.solicitudes.clients.TecnicosClient;
import utnfc.isi.back.tpi.solicitudes.dtos.CandidatoDto;
import utnfc.isi.back.tpi.solicitudes.dtos.ClienteDto;
import utnfc.isi.back.tpi.solicitudes.dtos.ElegirRequest;
import utnfc.isi.back.tpi.solicitudes.dtos.ElegirResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.HistorialResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.OpcionTecnicoResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.PresupuestoRequest;
import utnfc.isi.back.tpi.solicitudes.dtos.SolicitudRequest;
import utnfc.isi.back.tpi.solicitudes.dtos.SolicitudResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.TecnicoDto;
import utnfc.isi.back.tpi.solicitudes.exceptions.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.solicitudes.exceptions.ReglaNegocioException;
import utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud;
import utnfc.isi.back.tpi.solicitudes.models.HistorialEstado;
import utnfc.isi.back.tpi.solicitudes.models.Solicitud;
import utnfc.isi.back.tpi.solicitudes.repositories.HistorialEstadoRepository;
import utnfc.isi.back.tpi.solicitudes.repositories.SolicitudRepository;
import utnfc.isi.back.tpi.solicitudes.services.costos.CalculadoraCosto;
import utnfc.isi.back.tpi.solicitudes.services.costos.DetalleCosto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;

import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.ASIGNADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.CANCELADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.DERIVADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.EN_CURSO;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.EN_ESPERA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.FINALIZADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.OFERTADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.PENDIENTE;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.REPROGRAMADA;

/**
 * Orquesta el ciclo de vida de una solicitud (F4 a F9).
 * Toda la lógica de negocio vive acá; el controller solo traduce HTTP.
 */
@Service
public class SolicitudService {

    private static final Logger log = LoggerFactory.getLogger(SolicitudService.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    static final String USUARIO_SISTEMA = "sistema";

    private final SolicitudRepository solicitudRepository;
    private final HistorialEstadoRepository historialRepository;
    private final ClientesClient clientesClient;
    private final TecnicosClient tecnicosClient;
    private final GeoService geoService;
    private final CalculadoraCosto calculadoraCosto;
    private final int ventanaMinutos;
    private final int maxEnEspera;
    private final int maxEnCurso;

    public SolicitudService(SolicitudRepository solicitudRepository, HistorialEstadoRepository historialRepository,
                            ClientesClient clientesClient, TecnicosClient tecnicosClient, GeoService geoService,
                            CalculadoraCosto calculadoraCosto,
                            @Value("${app.oferta.ventana-minutos:15}") int ventanaMinutos,
                            @Value("${app.tecnico.max-en-espera:2}") int maxEnEspera,
                            @Value("${app.tecnico.max-en-curso:1}") int maxEnCurso) {
        this.solicitudRepository = solicitudRepository;
        this.historialRepository = historialRepository;
        this.clientesClient = clientesClient;
        this.tecnicosClient = tecnicosClient;
        this.geoService = geoService;
        this.calculadoraCosto = calculadoraCosto;
        this.ventanaMinutos = ventanaMinutos;
        this.maxEnEspera = maxEnEspera;
        this.maxEnCurso = maxEnCurso;
    }

    // ------------------------------------------------------------------ Consultas

    @Transactional(readOnly = true)
    public Page<SolicitudResponse> listar(EstadoSolicitud estado, Pageable pageable) {
        Page<Solicitud> pagina = estado == null
                ? solicitudRepository.findAll(pageable)
                : solicitudRepository.findByEstado(estado, pageable);
        return pagina.map(SolicitudResponse::desde);
    }

    @Transactional(readOnly = true)
    public SolicitudResponse obtener(Long id) {
        return SolicitudResponse.desde(buscar(id));
    }

    @Transactional(readOnly = true)
    public List<HistorialResponse> historial(Long id) {
        buscar(id);
        return historialRepository.findBySolicitudIdOrderByFechaHoraAscIdAsc(id).stream()
                .map(HistorialResponse::desde).toList();
    }

    // ------------------------------------------------------------------ F4: creación

    /**
     * Crea la solicitud en PENDIENTE copiando los datos del cliente (patrón Snapshot).
     * Si MS-Clientes no responde se lanza 503 y no se guarda nada (EN-09).
     */
    @Transactional
    public SolicitudResponse crear(SolicitudRequest request) {
        ClienteDto cliente = clientesClient.obtener(request.clienteId());

        Solicitud solicitud = Solicitud.builder()
                .clienteId(cliente.id())
                .clienteNombre(cliente.nombre() + " " + cliente.apellido())
                .domicilio(cliente.barrio() == null || cliente.barrio().isBlank()
                        ? cliente.calle() : cliente.calle() + ", " + cliente.barrio())
                .latitud(cliente.latitud())
                .longitud(cliente.longitud())
                .tiposServicio(new HashSet<>(request.tiposServicio()))
                .marca(request.marca())
                .modelo(request.modelo())
                .descripcion(request.descripcion())
                .especialidadId(request.especialidadId())
                .prioridad(request.prioridad())
                .fechaHoraProgramada(request.fechaHoraProgramada())
                .esFeriado(Boolean.TRUE.equals(request.esFeriado()))
                .estado(PENDIENTE)
                .fechaCreacion(LocalDateTime.now())
                .build();
        solicitudRepository.save(solicitud);
        registrarHistorial(solicitud, null, PENDIENTE, "Solicitud creada para el cliente " + solicitud.getClienteNombre());
        log.info("Solicitud {} creada para el cliente {}", solicitud.getId(), cliente.id());
        return SolicitudResponse.desde(solicitud);
    }

    // ------------------------------------------------------------------ F5: elegir candidatos

    /**
     * Busca candidatos (certificación + menos de 13 km, RN-2 y RN-9), descarta los que están al
     * límite de solicitudes (RN-5) y calcula el costo estimado de cada uno (RN-8, RN-11).
     */
    @Transactional(readOnly = true)
    public ElegirResponse elegir(ElegirRequest request) {
        List<CandidatoDto> candidatos = tecnicosClient.candidatos(request.especialidadId(), request.latitud(),
                request.longitud());
        boolean esFeriado = Boolean.TRUE.equals(request.esFeriado());
        List<OpcionTecnicoResponse> opciones = candidatos.stream()
                .filter(c -> !alcanzoLimiteEnEspera(c.tecnicoId()))
                .map(c -> {
                    DetalleCosto costo = calculadoraCosto.calcular(c.distanciaKm(), request.prioridad(),
                            request.fechaHoraProgramada(), esFeriado);
                    return new OpcionTecnicoResponse(c.tecnicoId(), c.nombre() + " " + c.apellido(),
                            c.distanciaKm(), Haversine.minutosEstimados(c.distanciaKm()), costo.estrategia(),
                            costo.costoBase(), costo.recargo(), costo.total());
                })
                .toList();
        if (opciones.isEmpty()) {
            log.warn("ALERTA ADMINISTRADOR: no hay técnicos candidatos para la especialidad {} en ({}, {})",
                    request.especialidadId(), request.latitud(), request.longitud());
            return new ElegirResponse(0, null, opciones,
                    "No hay técnicos disponibles: la solicitud queda en espera y se alertó al administrador");
        }
        return new ElegirResponse(opciones.size(), opciones.getFirst(), opciones,
                "Se encontraron " + opciones.size() + " técnicos candidatos");
    }

    // ------------------------------------------------------------------ F5/F6: oferta

    /** Envía la oferta a un técnico candidato y abre la ventana de aceptación (RN-3, RN-4, RN-5). */
    @Transactional
    public SolicitudResponse ofertar(Long id, Long tecnicoId) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, PENDIENTE, "enviar una oferta");
        validarLimiteEnEspera(tecnicoId);

        TecnicoDto tecnico = tecnicosClient.obtener(tecnicoId);
        boolean esCandidato = tecnicosClient
                .candidatos(solicitud.getEspecialidadId(), solicitud.getLatitud(), solicitud.getLongitud())
                .stream().anyMatch(c -> c.tecnicoId().equals(tecnicoId));
        if (!esCandidato) {
            throw new ReglaNegocioException("El técnico " + tecnicoId + " no es candidato para la solicitud " + id
                    + ": no está disponible, no tiene la certificación requerida o está a más de 13 km (RN-2, RN-9)");
        }

        solicitud.setTecnicoId(tecnico.id());
        solicitud.setTecnicoNombre(tecnico.nombre() + " " + tecnico.apellido());
        solicitud.setOfertaExpira(LocalDateTime.now().plusMinutes(ventanaMinutos));
        transicionar(solicitud, OFERTADA, "Oferta enviada al técnico " + solicitud.getTecnicoNombre()
                + ". Vence " + solicitud.getOfertaExpira().format(HORA) + " (" + ventanaMinutos + " min)");
        return SolicitudResponse.desde(solicitud);
    }

    /** El técnico acepta dentro de la ventana: se calcula el costo final con la distancia real (RN-8). */
    @Transactional
    public SolicitudResponse aceptar(Long id, Long tecnicoId) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, OFERTADA, "aceptar la oferta");
        exigirTecnicoOfertado(solicitud, tecnicoId);
        if (solicitud.getOfertaExpira().isBefore(LocalDateTime.now())) {
            throw new ReglaNegocioException("La oferta de la solicitud " + id + " venció a las "
                    + solicitud.getOfertaExpira().format(HORA) + " (RN-3)");
        }

        TecnicoDto tecnico = tecnicosClient.obtener(tecnicoId);
        Ruta ruta = geoService.calcularRuta(tecnico.latitud(), tecnico.longitud(),
                solicitud.getLatitud(), solicitud.getLongitud());
        DetalleCosto costo = calculadoraCosto.calcular(ruta.kilometros(), solicitud.getPrioridad(),
                solicitud.getFechaHoraProgramada(), solicitud.getEsFeriado());

        solicitud.setDistanciaKm(ruta.kilometros());
        solicitud.setTiempoEstimadoMin(ruta.minutos());
        solicitud.setFuenteDistancia(ruta.fuente().name());
        solicitud.setEstrategiaCosto(costo.estrategia());
        solicitud.setCostoBase(costo.costoBase());
        solicitud.setRecargo(costo.recargo());
        solicitud.setTotal(costo.total());
        solicitud.setOfertaExpira(null);
        transicionar(solicitud, ASIGNADA, "Técnico " + solicitud.getTecnicoNombre() + " aceptó. Costo de visita $"
                + costo.total() + " (" + costo.estrategia() + ", " + ruta.kilometros() + " km por " + ruta.fuente() + ")");
        return SolicitudResponse.desde(solicitud);
    }

    /** El técnico rechaza: la solicitud vuelve a PENDIENTE para ofertarla a otro (RN-3). */
    @Transactional
    public SolicitudResponse rechazar(Long id, Long tecnicoId) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, OFERTADA, "rechazar la oferta");
        exigirTecnicoOfertado(solicitud, tecnicoId);
        String tecnico = solicitud.getTecnicoNombre();
        liberarTecnico(solicitud);
        transicionar(solicitud, PENDIENTE, "Técnico " + tecnico + " rechazó la oferta");
        return SolicitudResponse.desde(solicitud);
    }

    /**
     * Ventana de 15 minutos (RN-3): las ofertas vencidas vuelven a PENDIENTE.
     * La llama el {@link OfertaScheduler}. @Version evita que choque con una aceptación simultánea.
     */
    @Transactional
    public int expirarOfertasVencidas() {
        List<Solicitud> vencidas = solicitudRepository.findByEstadoAndOfertaExpiraBefore(OFERTADA, LocalDateTime.now());
        for (Solicitud solicitud : vencidas) {
            String tecnico = solicitud.getTecnicoNombre();
            liberarTecnico(solicitud);
            transicionar(solicitud, PENDIENTE, "Oferta al técnico " + tecnico + " vencida: no respondió en "
                    + ventanaMinutos + " min", USUARIO_SISTEMA);
            log.info("Oferta de la solicitud {} vencida, vuelve a PENDIENTE", solicitud.getId());
        }
        return vencidas.size();
    }

    // ------------------------------------------------------------------ F7: diagnóstico y presupuesto

    @Transactional
    public SolicitudResponse cargarPresupuesto(Long id, PresupuestoRequest request) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, ASIGNADA, "cargar el presupuesto");
        solicitud.setDiagnostico(request.diagnostico());
        solicitud.setPresupuesto(request.importe());
        registrarHistorial(solicitud, ASIGNADA, ASIGNADA, "Técnico cargó diagnóstico y presupuesto por $"
                + request.importe() + ": " + request.diagnostico());
        return SolicitudResponse.desde(solicitud);
    }

    /** El cliente aprueba el presupuesto: pasa a EN_CURSO (RN-6) si el técnico no tiene otro en curso (RN-5). */
    @Transactional
    public SolicitudResponse aprobarPresupuesto(Long id) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, ASIGNADA, "aprobar el presupuesto");
        if (solicitud.getPresupuesto() == null) {
            throw new ReglaNegocioException("La solicitud " + id + " todavía no tiene presupuesto cargado por el técnico (RN-6)");
        }
        long enCurso = solicitudRepository.countByTecnicoIdAndEstado(solicitud.getTecnicoId(), EN_CURSO);
        if (enCurso >= maxEnCurso) {
            throw new ReglaNegocioException("El técnico " + solicitud.getTecnicoId() + " ya tiene " + enCurso
                    + " solicitud en curso (máximo " + maxEnCurso + "). Esperá a que la finalice (RN-5)");
        }
        transicionar(solicitud, EN_CURSO, "Cliente aprobó presupuesto por $" + solicitud.getPresupuesto());
        return SolicitudResponse.desde(solicitud);
    }

    // ------------------------------------------------------------------ Cierre y excepciones del flujo

    /** Cancela la solicitud (por ejemplo, el cliente rechaza el presupuesto). */
    @Transactional
    public SolicitudResponse cancelar(Long id, String motivo) {
        Solicitud solicitud = buscar(id);
        EstadoSolicitud anterior = solicitud.getEstado();
        validarTransicion(solicitud, CANCELADA, "cancelar");
        if (anterior == OFERTADA) {
            solicitud.setOfertaExpira(null);
        }
        transicionar(solicitud, CANCELADA, motivo == null || motivo.isBlank() ? "Solicitud cancelada" : motivo);
        return SolicitudResponse.desde(solicitud);
    }

    @Transactional
    public SolicitudResponse finalizar(Long id) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, EN_CURSO, "finalizar el trabajo");
        transicionar(solicitud, FINALIZADA, "Trabajo finalizado. Visita $" + solicitud.getTotal()
                + " + presupuesto $" + solicitud.getPresupuesto() + " = $"
                + solicitud.getTotal().add(solicitud.getPresupuesto()));
        return SolicitudResponse.desde(solicitud);
    }

    /** Falta de repuestos: se pausa el trabajo. */
    @Transactional
    public SolicitudResponse reprogramar(Long id, String motivo) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, EN_CURSO, "reprogramar");
        transicionar(solicitud, REPROGRAMADA, motivo == null || motivo.isBlank() ? "Falta de repuestos" : motivo);
        return SolicitudResponse.desde(solicitud);
    }

    @Transactional
    public SolicitudResponse reanudar(Long id) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, REPROGRAMADA, "reanudar");
        transicionar(solicitud, EN_CURSO, "Se consiguió el repuesto, se reanuda el trabajo");
        return SolicitudResponse.desde(solicitud);
    }

    /** La falla supera la certificación del técnico: se libera y vuelve a PENDIENTE para reasignar. */
    @Transactional
    public SolicitudResponse derivar(Long id, String motivo) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, EN_CURSO, "derivar");
        String tecnico = solicitud.getTecnicoNombre();
        transicionar(solicitud, DERIVADA, (motivo == null || motivo.isBlank()
                ? "La falla supera la certificación" : motivo) + " del técnico " + tecnico);
        liberarTecnico(solicitud);
        solicitud.setDistanciaKm(null);
        solicitud.setTiempoEstimadoMin(null);
        solicitud.setFuenteDistancia(null);
        solicitud.setEstrategiaCosto(null);
        solicitud.setCostoBase(null);
        solicitud.setRecargo(null);
        solicitud.setTotal(null);
        solicitud.setDiagnostico(null);
        solicitud.setPresupuesto(null);
        transicionar(solicitud, PENDIENTE, "Vuelve a asignación para buscar otro técnico (RN-2)");
        return SolicitudResponse.desde(solicitud);
    }

    // ------------------------------------------------------------------ Reglas auxiliares

    private Solicitud buscar(Long id) {
        return solicitudRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la solicitud con id " + id));
    }

    private void exigirEstado(Solicitud solicitud, EstadoSolicitud esperado, String operacion) {
        if (solicitud.getEstado() != esperado) {
            throw new ReglaNegocioException("La solicitud " + solicitud.getId() + " está en estado "
                    + solicitud.getEstado() + "; para " + operacion + " debe estar en " + esperado + " (RN-7)");
        }
    }

    private void validarTransicion(Solicitud solicitud, EstadoSolicitud destino, String operacion) {
        if (!solicitud.getEstado().puedePasarA(destino)) {
            throw new ReglaNegocioException("No se puede " + operacion + " la solicitud " + solicitud.getId()
                    + " porque está en estado " + solicitud.getEstado() + " (RN-7)");
        }
    }

    /** RN-4: solo el técnico que recibió la oferta puede responderla. */
    private void exigirTecnicoOfertado(Solicitud solicitud, Long tecnicoId) {
        if (!tecnicoId.equals(solicitud.getTecnicoId())) {
            throw new ReglaNegocioException("La oferta de la solicitud " + solicitud.getId()
                    + " fue enviada al técnico " + solicitud.getTecnicoId() + ", no al " + tecnicoId + " (RN-4)");
        }
    }

    private boolean alcanzoLimiteEnEspera(Long tecnicoId) {
        return solicitudRepository.countByTecnicoIdAndEstadoIn(tecnicoId, EN_ESPERA) >= maxEnEspera;
    }

    /** RN-5: un técnico acumula como máximo 2 solicitudes en espera (ofertadas, asignadas o reprogramadas). */
    private void validarLimiteEnEspera(Long tecnicoId) {
        long enEspera = solicitudRepository.countByTecnicoIdAndEstadoIn(tecnicoId, EN_ESPERA);
        if (enEspera >= maxEnEspera) {
            long enCurso = solicitudRepository.countByTecnicoIdAndEstado(tecnicoId, EN_CURSO);
            throw new ReglaNegocioException("El técnico " + tecnicoId + " alcanzó el límite de solicitudes: tiene "
                    + enEspera + " en espera (máximo " + maxEnEspera + ") y " + enCurso + " en curso (RN-5)");
        }
    }

    private static void liberarTecnico(Solicitud solicitud) {
        solicitud.setTecnicoId(null);
        solicitud.setTecnicoNombre(null);
        solicitud.setOfertaExpira(null);
    }

    private void transicionar(Solicitud solicitud, EstadoSolicitud destino, String observaciones) {
        transicionar(solicitud, destino, observaciones, usuarioActual());
    }

    private void transicionar(Solicitud solicitud, EstadoSolicitud destino, String observaciones, String usuario) {
        EstadoSolicitud anterior = solicitud.getEstado();
        if (!anterior.puedePasarA(destino)) {
            throw new ReglaNegocioException("Transición inválida de " + anterior + " a " + destino
                    + " en la solicitud " + solicitud.getId() + " (RN-7)");
        }
        solicitud.setEstado(destino);
        registrarHistorial(solicitud, anterior, destino, observaciones, usuario);
        log.info("Solicitud {}: {} -> {}", solicitud.getId(), anterior, destino);
    }

    private void registrarHistorial(Solicitud solicitud, EstadoSolicitud anterior, EstadoSolicitud nuevo,
                                    String observaciones) {
        registrarHistorial(solicitud, anterior, nuevo, observaciones, usuarioActual());
    }

    private void registrarHistorial(Solicitud solicitud, EstadoSolicitud anterior, EstadoSolicitud nuevo,
                                    String observaciones, String usuario) {
        historialRepository.save(HistorialEstado.builder()
                .solicitud(solicitud)
                .estadoAnterior(anterior)
                .estadoNuevo(nuevo)
                .fechaHora(LocalDateTime.now())
                .observaciones(observaciones.length() > 500 ? observaciones.substring(0, 500) : observaciones)
                .usuario(usuario)
                .build());
    }

    private static String usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? USUARIO_SISTEMA : auth.getName();
    }
}
