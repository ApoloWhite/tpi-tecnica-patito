package utnfc.isi.back.tpi.tecnicos.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import utnfc.isi.back.tpi.tecnicos.dtos.CandidatoResponse;
import utnfc.isi.back.tpi.tecnicos.dtos.DisponibilidadRequest;
import utnfc.isi.back.tpi.tecnicos.dtos.TecnicoRequest;
import utnfc.isi.back.tpi.tecnicos.dtos.TecnicoResponse;
import utnfc.isi.back.tpi.tecnicos.services.TecnicoService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tecnicos")
@Tag(name = "Técnicos", description = "Padrón de técnicos y búsqueda de candidatos (F3, F5)")
public class TecnicoController {

    private final TecnicoService tecnicoService;

    public TecnicoController(TecnicoService tecnicoService) {
        this.tecnicoService = tecnicoService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Operation(summary = "Registrar un técnico",
            description = "Rol: ADMINISTRADOR. Incluye certificaciones y zonas de cobertura. DNI y legajo únicos.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Técnico creado"),
            @ApiResponse(responseCode = "400", description = "Faltan datos o tienen formato inválido"),
            @ApiResponse(responseCode = "403", description = "Rol sin permiso (por ejemplo, CLIENTE)"),
            @ApiResponse(responseCode = "422", description = "DNI o legajo duplicado, o certificación/zona inexistente")
    })
    public ResponseEntity<TecnicoResponse> crear(@Valid @RequestBody TecnicoRequest request) {
        TecnicoResponse creado = tecnicoService.crear(request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{tecnicoId}").buildAndExpand(creado.id()).toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO')")
    @Operation(summary = "Listar técnicos (paginado)", description = "Cualquier rol. Filtro opcional por disponibilidad.")
    @ApiResponse(responseCode = "200", description = "Página de técnicos")
    public ResponseEntity<Page<TecnicoResponse>> listar(
            @Parameter(description = "true = solo disponibles, false = solo no disponibles")
            @RequestParam(required = false) Boolean disponible,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(tecnicoService.listar(disponible, pageable));
    }

    @GetMapping("/candidatos")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO')")
    @Operation(summary = "Buscar candidatos para una solicitud",
            description = "Técnicos disponibles, con la certificación pedida y a menos de 13 km (Haversine). "
                    + "Ordenados del más cercano al más lejano (RN-2, RN-9).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de candidatos (puede estar vacía)"),
            @ApiResponse(responseCode = "400", description = "Parámetros faltantes o fuera de rango"),
            @ApiResponse(responseCode = "404", description = "La certificación no existe")
    })
    public ResponseEntity<List<CandidatoResponse>> candidatos(
            @Parameter(description = "Id de la certificación requerida", example = "1")
            @RequestParam Long certificacionId,
            @Parameter(description = "Latitud del cliente", example = "-31.420")
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") Double latitud,
            @Parameter(description = "Longitud del cliente", example = "-64.188")
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") Double longitud) {
        return ResponseEntity.ok(tecnicoService.buscarCandidatos(certificacionId, latitud, longitud));
    }

    @GetMapping("/{tecnicoId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO')")
    @Operation(summary = "Consultar un técnico", description = "Cualquier rol. Incluye disponibilidad y certificaciones.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Técnico encontrado"),
            @ApiResponse(responseCode = "404", description = "El técnico no existe")
    })
    public ResponseEntity<TecnicoResponse> obtener(@PathVariable Long tecnicoId) {
        return ResponseEntity.ok(tecnicoService.obtener(tecnicoId));
    }

    @PatchMapping("/{tecnicoId}/disponibilidad")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','TECNICO')")
    @Operation(summary = "Cambiar la disponibilidad de un técnico", description = "Roles: ADMINISTRADOR, TECNICO.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Disponibilidad actualizada"),
            @ApiResponse(responseCode = "404", description = "El técnico no existe")
    })
    public ResponseEntity<TecnicoResponse> cambiarDisponibilidad(@PathVariable Long tecnicoId,
                                                                 @Valid @RequestBody DisponibilidadRequest request) {
        return ResponseEntity.ok(tecnicoService.cambiarDisponibilidad(tecnicoId, request.disponible()));
    }
}
