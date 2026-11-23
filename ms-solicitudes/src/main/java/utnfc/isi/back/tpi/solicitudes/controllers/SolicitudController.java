package utnfc.isi.back.tpi.solicitudes.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import utnfc.isi.back.tpi.solicitudes.dtos.ElegirRequest;
import utnfc.isi.back.tpi.solicitudes.dtos.ElegirResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.HistorialResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.InformeResponse;
import utnfc.isi.back.tpi.solicitudes.dtos.PresupuestoRequest;
import utnfc.isi.back.tpi.solicitudes.dtos.SolicitudRequest;
import utnfc.isi.back.tpi.solicitudes.dtos.SolicitudResponse;
import utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud;
import utnfc.isi.back.tpi.solicitudes.services.InformeService;
import utnfc.isi.back.tpi.solicitudes.services.SolicitudService;

import org.springframework.format.annotation.DateTimeFormat;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/solicitudes")
@Tag(name = "Solicitudes", description = "Ciclo de vida de una solicitud de servicio técnico (F4 a F9)")
public class SolicitudController {

    private static final String ROLES_TODOS = "hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO')";
    private static final String ROLES_CLIENTE = "hasAnyRole('ADMINISTRADOR','CLIENTE')";
    private static final String ROLES_TECNICO = "hasAnyRole('ADMINISTRADOR','TECNICO')";

    private final SolicitudService solicitudService;
    private final InformeService informeService;

    public SolicitudController(SolicitudService solicitudService, InformeService informeService) {
        this.solicitudService = solicitudService;
        this.informeService = informeService;
    }

    @PostMapping
    @PreAuthorize(ROLES_CLIENTE)
    @Operation(summary = "Crear una solicitud",
            description = "Roles: ADMINISTRADOR, CLIENTE. Consulta MS-Clientes y copia sus datos (snapshot). Estado inicial PENDIENTE (F4, RN-1).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Solicitud creada en PENDIENTE"),
            @ApiResponse(responseCode = "400", description = "Faltan marca, modelo, descripción o tipos de servicio (RN-1)"),
            @ApiResponse(responseCode = "404", description = "El cliente no existe (EN-05)"),
            @ApiResponse(responseCode = "503", description = "MS-Clientes no disponible (EN-09)")
    })
    public ResponseEntity<SolicitudResponse> crear(@Valid @RequestBody SolicitudRequest request) {
        SolicitudResponse creada = solicitudService.crear(request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{solicitudId}").buildAndExpand(creada.id()).toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    @GetMapping
    @PreAuthorize(ROLES_TODOS)
    @Operation(summary = "Listar solicitudes (paginado)", description = "Cualquier rol. Filtro opcional por estado.")
    public ResponseEntity<Page<SolicitudResponse>> listar(
            @Parameter(description = "Estado a filtrar", example = "PENDIENTE")
            @RequestParam(required = false) EstadoSolicitud estado,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(solicitudService.listar(estado, pageable));
    }

    @GetMapping("/informe")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Operation(summary = "Informe de gestión",
            description = "Rol: ADMINISTRADOR. Cantidades por estado, modo, prioridad y tipo; uso de cada estrategia de costo; "
                    + "facturación de las finalizadas; embudo de ofertas (enviadas, aceptadas, rechazadas, vencidas) "
                    + "y desempeño por técnico. Filtra por fecha de creación (ambas fechas incluidas).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Informe generado"),
            @ApiResponse(responseCode = "400", description = "Fecha con formato inválido (usar AAAA-MM-DD)"),
            @ApiResponse(responseCode = "403", description = "Solo el ADMINISTRADOR puede ver el informe"),
            @ApiResponse(responseCode = "422", description = "'desde' es posterior a 'hasta'")
    })
    public ResponseEntity<InformeResponse> informe(
            @Parameter(description = "Primer día incluido (AAAA-MM-DD)", example = "2026-11-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Último día incluido (AAAA-MM-DD)", example = "2026-11-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(informeService.generar(desde, hasta));
    }

    @GetMapping("/{solicitudId}")
    @PreAuthorize(ROLES_TODOS)
    @Operation(summary = "Consultar una solicitud", description = "Cualquier rol. Detalle y estado actual.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud encontrada"),
            @ApiResponse(responseCode = "404", description = "La solicitud no existe")
    })
    public ResponseEntity<SolicitudResponse> obtener(@PathVariable Long solicitudId) {
        return ResponseEntity.ok(solicitudService.obtener(solicitudId));
    }

    @GetMapping("/{solicitudId}/historial")
    @PreAuthorize(ROLES_TODOS)
    @Operation(summary = "Historial de estados", description = "Cualquier rol. Todas las transiciones con fecha, hora y usuario (F8, RN-7).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial ordenado del más viejo al más nuevo"),
            @ApiResponse(responseCode = "404", description = "La solicitud no existe")
    })
    public ResponseEntity<List<HistorialResponse>> historial(@PathVariable Long solicitudId) {
        return ResponseEntity.ok(solicitudService.historial(solicitudId));
    }

    @PostMapping("/elegir")
    @PreAuthorize(ROLES_CLIENTE)
    @Operation(summary = "Probar candidatos y costo",
            description = "Roles: ADMINISTRADOR, CLIENTE. Técnicos con la certificación, a menos de 13 km y sin exceder su límite, "
                    + "con el costo estimado según la estrategia que corresponda (RN-2, RN-5, RN-9, RN-11).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Candidatos ordenados por cercanía (puede estar vacío)"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "La especialidad no existe"),
            @ApiResponse(responseCode = "503", description = "MS-Técnicos no disponible")
    })
    public ResponseEntity<ElegirResponse> elegir(@Valid @RequestBody ElegirRequest request) {
        return ResponseEntity.ok(solicitudService.elegir(request));
    }

    @PostMapping("/{solicitudId}/ofertar/{tecnicoId}")
    @PreAuthorize(ROLES_CLIENTE)
    @Operation(summary = "Ofertar a un técnico",
            description = "Roles: ADMINISTRADOR, CLIENTE. PENDIENTE -> OFERTADA. Abre la ventana de aceptación (RN-3) "
                    + "y valida que el técnico sea candidato y no esté al límite (RN-2, RN-5).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Oferta enviada, estado OFERTADA con ofertaExpira"),
            @ApiResponse(responseCode = "404", description = "La solicitud o el técnico no existen"),
            @ApiResponse(responseCode = "422", description = "Estado inválido, técnico no candidato o al límite (EN-06)")
    })
    public ResponseEntity<SolicitudResponse> ofertar(@PathVariable Long solicitudId, @PathVariable Long tecnicoId) {
        return ResponseEntity.ok(solicitudService.ofertar(solicitudId, tecnicoId));
    }

    @PostMapping("/{solicitudId}/aceptar/{tecnicoId}")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "El técnico acepta la oferta",
            description = "Roles: ADMINISTRADOR, TECNICO. OFERTADA -> ASIGNADA. Calcula el costo con la distancia real "
                    + "(OSRM, o Haversine si falla) (RN-4, RN-8).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud ASIGNADA con el costo calculado"),
            @ApiResponse(responseCode = "422", description = "Estado inválido, otro técnico u oferta vencida")
    })
    public ResponseEntity<SolicitudResponse> aceptar(@PathVariable Long solicitudId, @PathVariable Long tecnicoId) {
        return ResponseEntity.ok(solicitudService.aceptar(solicitudId, tecnicoId));
    }

    @PostMapping("/{solicitudId}/rechazar/{tecnicoId}")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "El técnico rechaza la oferta", description = "Roles: ADMINISTRADOR, TECNICO. OFERTADA -> PENDIENTE (RN-3).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud de nuevo en PENDIENTE"),
            @ApiResponse(responseCode = "422", description = "Estado inválido u otro técnico")
    })
    public ResponseEntity<SolicitudResponse> rechazar(@PathVariable Long solicitudId, @PathVariable Long tecnicoId) {
        return ResponseEntity.ok(solicitudService.rechazar(solicitudId, tecnicoId));
    }

    @PostMapping("/{solicitudId}/presupuesto")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "Cargar diagnóstico y presupuesto", description = "Roles: ADMINISTRADOR, TECNICO. La solicitud debe estar ASIGNADA (F7).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presupuesto cargado"),
            @ApiResponse(responseCode = "400", description = "Falta el diagnóstico o el importe"),
            @ApiResponse(responseCode = "422", description = "La solicitud no está ASIGNADA")
    })
    public ResponseEntity<SolicitudResponse> cargarPresupuesto(@PathVariable Long solicitudId,
                                                               @Valid @RequestBody PresupuestoRequest request) {
        return ResponseEntity.ok(solicitudService.cargarPresupuesto(solicitudId, request));
    }

    @PostMapping("/{solicitudId}/aprobar-presupuesto")
    @PreAuthorize(ROLES_CLIENTE)
    @Operation(summary = "El cliente aprueba el presupuesto",
            description = "Roles: ADMINISTRADOR, CLIENTE. ASIGNADA -> EN_CURSO (RN-6). El técnico no puede tener otra en curso (RN-5).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud EN_CURSO"),
            @ApiResponse(responseCode = "422", description = "Estado inválido (EN-08), sin presupuesto o técnico con otra en curso")
    })
    public ResponseEntity<SolicitudResponse> aprobarPresupuesto(@PathVariable Long solicitudId) {
        return ResponseEntity.ok(solicitudService.aprobarPresupuesto(solicitudId));
    }

    @PostMapping("/{solicitudId}/cancelar")
    @PreAuthorize(ROLES_CLIENTE)
    @Operation(summary = "Cancelar la solicitud",
            description = "Roles: ADMINISTRADOR, CLIENTE. Desde PENDIENTE, OFERTADA o ASIGNADA (por ejemplo, si el cliente rechaza el presupuesto).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud CANCELADA"),
            @ApiResponse(responseCode = "422", description = "No se puede cancelar en el estado actual")
    })
    public ResponseEntity<SolicitudResponse> cancelar(
            @PathVariable Long solicitudId,
            @Parameter(description = "Motivo de la cancelación", example = "Cliente rechazó el presupuesto")
            @RequestParam(required = false) String motivo) {
        return ResponseEntity.ok(solicitudService.cancelar(solicitudId, motivo));
    }

    @PostMapping("/{solicitudId}/reprogramar")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "Reprogramar por falta de repuestos", description = "Roles: ADMINISTRADOR, TECNICO. EN_CURSO -> REPROGRAMADA.")
    public ResponseEntity<SolicitudResponse> reprogramar(
            @PathVariable Long solicitudId,
            @Parameter(example = "Falta el rodillo de arrastre") @RequestParam(required = false) String motivo) {
        return ResponseEntity.ok(solicitudService.reprogramar(solicitudId, motivo));
    }

    @PostMapping("/{solicitudId}/reanudar")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "Reanudar una solicitud reprogramada", description = "Roles: ADMINISTRADOR, TECNICO. REPROGRAMADA -> EN_CURSO.")
    public ResponseEntity<SolicitudResponse> reanudar(@PathVariable Long solicitudId) {
        return ResponseEntity.ok(solicitudService.reanudar(solicitudId));
    }

    @PostMapping("/{solicitudId}/derivar")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "Derivar a otro técnico",
            description = "Roles: ADMINISTRADOR, TECNICO. EN_CURSO -> DERIVADA -> PENDIENTE. Libera al técnico para reasignar.")
    public ResponseEntity<SolicitudResponse> derivar(
            @PathVariable Long solicitudId,
            @Parameter(example = "La placa requiere certificación Notebooks Apple") @RequestParam(required = false) String motivo) {
        return ResponseEntity.ok(solicitudService.derivar(solicitudId, motivo));
    }

    @PostMapping("/{solicitudId}/finalizar")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "Finalizar el trabajo", description = "Roles: ADMINISTRADOR, TECNICO. EN_CURSO -> FINALIZADA (F9).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud FINALIZADA"),
            @ApiResponse(responseCode = "422", description = "La solicitud no está EN_CURSO")
    })
    public ResponseEntity<SolicitudResponse> finalizar(@PathVariable Long solicitudId) {
        return ResponseEntity.ok(solicitudService.finalizar(solicitudId));
    }
}
