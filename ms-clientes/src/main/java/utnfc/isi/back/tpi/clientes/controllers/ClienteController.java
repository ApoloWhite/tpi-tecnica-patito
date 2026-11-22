package utnfc.isi.back.tpi.clientes.controllers;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import utnfc.isi.back.tpi.clientes.dtos.ClienteRequest;
import utnfc.isi.back.tpi.clientes.dtos.ClienteResponse;
import utnfc.isi.back.tpi.clientes.services.ClienteService;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/clientes")
@Tag(name = "Clientes", description = "Padrón de clientes con domicilio y coordenadas (F2)")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE')")
    @Operation(summary = "Registrar un cliente", description = "Roles: ADMINISTRADOR, CLIENTE. El DNI no se puede repetir.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cliente creado. El header Location tiene su URI"),
            @ApiResponse(responseCode = "400", description = "Faltan datos o tienen formato inválido"),
            @ApiResponse(responseCode = "401", description = "Sin token"),
            @ApiResponse(responseCode = "403", description = "Rol sin permiso"),
            @ApiResponse(responseCode = "422", description = "DNI duplicado")
    })
    public ResponseEntity<ClienteResponse> crear(@Valid @RequestBody ClienteRequest request) {
        ClienteResponse creado = clienteService.crear(request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{clienteId}").buildAndExpand(creado.id()).toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Operation(summary = "Listar clientes (paginado)", description = "Rol: ADMINISTRADOR. Filtro opcional por apellido.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de clientes"),
            @ApiResponse(responseCode = "403", description = "Rol sin permiso")
    })
    public ResponseEntity<Page<ClienteResponse>> listar(
            @Parameter(description = "Parte del apellido a buscar", example = "garc")
            @RequestParam(required = false) String apellido,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(clienteService.listar(apellido, pageable));
    }

    @GetMapping("/{clienteId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','SISTEMA')")
    @Operation(summary = "Consultar un cliente", description = "Roles: ADMINISTRADOR, CLIENTE. Incluye las coordenadas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cliente encontrado"),
            @ApiResponse(responseCode = "404", description = "El cliente no existe")
    })
    public ResponseEntity<ClienteResponse> obtener(@PathVariable Long clienteId) {
        return ResponseEntity.ok(clienteService.obtener(clienteId));
    }

    @PutMapping("/{clienteId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE')")
    @Operation(summary = "Modificar un cliente", description = "Roles: ADMINISTRADOR, CLIENTE. Reemplaza todos los datos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cliente modificado"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "El cliente no existe"),
            @ApiResponse(responseCode = "422", description = "DNI usado por otro cliente")
    })
    public ResponseEntity<ClienteResponse> actualizar(@PathVariable Long clienteId,
                                                      @Valid @RequestBody ClienteRequest request) {
        return ResponseEntity.ok(clienteService.actualizar(clienteId, request));
    }

    @DeleteMapping("/{clienteId}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Operation(summary = "Eliminar un cliente", description = "Rol: ADMINISTRADOR.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cliente eliminado"),
            @ApiResponse(responseCode = "404", description = "El cliente no existe")
    })
    public ResponseEntity<Void> eliminar(@PathVariable Long clienteId) {
        clienteService.eliminar(clienteId);
        return ResponseEntity.noContent().build();
    }
}
