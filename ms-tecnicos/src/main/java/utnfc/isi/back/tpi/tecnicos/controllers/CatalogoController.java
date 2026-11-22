package utnfc.isi.back.tpi.tecnicos.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utnfc.isi.back.tpi.tecnicos.dtos.CatalogoResponse;
import utnfc.isi.back.tpi.tecnicos.services.CatalogoService;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catálogos", description = "Certificaciones y zonas de cobertura")
public class CatalogoController {

    private final CatalogoService catalogoService;

    public CatalogoController(CatalogoService catalogoService) {
        this.catalogoService = catalogoService;
    }

    @GetMapping("/certificaciones")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO','SISTEMA')")
    @Operation(summary = "Listar certificaciones", description = "Cualquier rol. El id se usa como especialidadId en las solicitudes.")
    public ResponseEntity<List<CatalogoResponse>> certificaciones() {
        return ResponseEntity.ok(catalogoService.listarCertificaciones());
    }

    @GetMapping("/zonas")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO','SISTEMA')")
    @Operation(summary = "Listar zonas de cobertura", description = "Cualquier rol.")
    public ResponseEntity<List<CatalogoResponse>> zonas() {
        return ResponseEntity.ok(catalogoService.listarZonas());
    }
}
