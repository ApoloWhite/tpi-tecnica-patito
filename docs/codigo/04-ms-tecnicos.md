# 04 – ms-tecnicos: la sala de los técnicos

## ¿Para qué sirve? (explicado fácil)

Es el **cuaderno de técnicos**: quién es cada uno, dónde tiene su base (latitud y longitud), si hoy está **disponible**, qué sabe arreglar (sus **certificaciones**) y en qué **zonas** trabaja.

Y tiene un superpoder: la **búsqueda de candidatos**. Le decís *"necesito alguien que sepa Redes para un cliente que vive en tal lugar"* y te devuelve la lista de técnicos que:
1. están disponibles;
2. tienen esa certificación;
3. están a **menos de 13 km** (RN-9),

ordenada del más cercano al más lejano.

> 📌 Las clases `SecurityConfig`, `CorrelationIdFilter`, `OpenApiConfig`, `GlobalExceptionHandler`, `RecursoNoEncontradoException` y `ReglaNegocioException` son iguales a las de ms-clientes (ver [03-ms-clientes.md](03-ms-clientes.md)). Abajo solo se explica lo distinto.

```
ms-tecnicos/src/main/java/utnfc/isi/back/tpi/tecnicos/
├── MsTecnicosApplication.java
├── config/      SecurityConfig (+1 regla), CorrelationIdFilter, OpenApiConfig, DataLoader
├── models/      Tecnico, Certificacion, ZonaCobertura, TecnicoCertificacion, TecnicoZona
├── dtos/        TecnicoRequest, TecnicoResponse, CatalogoResponse, CandidatoResponse, DisponibilidadRequest
├── repositories/ TecnicoRepository, CertificacionRepository, ZonaCoberturaRepository,
│                 TecnicoCertificacionRepository, TecnicoZonaRepository
├── services/    TecnicoService, CatalogoService
├── controllers/ TecnicoController, CatalogoController
└── exceptions/  (iguales a ms-clientes)
```

---

## La regla extra en `SecurityConfig`

```java
                        .requestMatchers("/actuator/**").hasRole("ADMINISTRADOR")
                        // Alta de técnicos solo para ADMINISTRADOR: se corta acá, antes de validar el body (EN-03)
                        .requestMatchers(HttpMethod.POST, "/api/v1/tecnicos").hasRole("ADMINISTRADOR")
```
- Una regla **por ruta y método** (lineamiento §8): un `POST` a `/api/v1/tecnicos` exige `ADMINISTRADOR`.
- **¿Por qué si ya está `@PreAuthorize`?** Por el orden en que pasan las cosas: Spring valida el cuerpo (`@Valid`) **antes** de ejecutar `@PreAuthorize`. Si un CLIENTE mandaba un cuerpo incompleto, recibía **400** ("faltan datos") en vez de **403** ("no tenés permiso"). Con esta regla, el rechazo ocurre en el filtro de seguridad, antes de mirar el cuerpo. Las dos barreras conviven.

---

## Los modelos: 5 tablas

```
 TECNICO ─────┐                       ┌───── CERTIFICACION
 (Juan, Ana…) │                       │      (Redes, Impresoras…)
              ▼                       ▼
         TECNICO_CERTIFICACION  (qué sabe cada uno)

 TECNICO ─────┐                       ┌───── ZONA_COBERTURA
              ▼                       ▼      (Centro, Nueva Córdoba)
              TECNICO_ZONA      (dónde trabaja cada uno)
```

Un técnico puede tener muchas certificaciones, y una certificación la pueden tener muchos técnicos: es una relación **muchos a muchos**. Se resuelve con una **tabla del medio**.

### `models/Tecnico.java`

```java
@Entity
@Table(name = "tecnico")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Tecnico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String legajo;
    ...
    @Column(nullable = false, unique = true, length = 15)
    private String dni;

    @Column(nullable = false)
    private Double latitud;

    @Column(nullable = false)
    private Double longitud;

    @Column(nullable = false)
    private Boolean disponible;
}
```
- Igual que `Cliente` (ver ms-clientes), con dos datos **únicos**: `legajo` (el número de empleado, que pide F3) y `dni`.
- `latitud` y `longitud`: la **base** del técnico, desde donde sale.
- `disponible`: si está en `false`, no recibe ofertas (RN-2).

### `models/Certificacion.java` y `models/ZonaCobertura.java`

```java
@Entity
@Table(name = "certificacion")
...
public class Certificacion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String nombre;
}
```
- Catálogos simples: un id y un nombre único. `ZonaCobertura` es idéntica, con la tabla `zona_cobertura`.
- El **id de la certificación** es lo que en las solicitudes se llama `especialidadId` (1 = Redes, 2 = Impresoras, 3 = Notebooks Apple).

### `models/TecnicoCertificacion.java`: la tabla del medio

```java
@Entity
@Table(name = "tecnico_certificacion",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tecnico_id", "certificacion_id"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class TecnicoCertificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tecnico_id")
    @ToString.Exclude
    private Tecnico tecnico;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "certificacion_id")
    @ToString.Exclude
    private Certificacion certificacion;
}
```
- Cada fila dice *"el técnico X tiene la certificación Y"*.
- `uniqueConstraints = @UniqueConstraint(columnNames = {"tecnico_id", "certificacion_id"})`: la base no deja repetir el mismo par (Juan no puede tener "Redes" dos veces).
- `@ManyToOne`: "**muchas** filas de esta tabla apuntan a **un** técnico". Es la relación N→1.
- `fetch = FetchType.LAZY`: el técnico **no** se carga de la base hasta que alguien lo use (`tc.getTecnico().getNombre()`). Ahorra consultas. Los lineamientos piden LAZY siempre (§4).
- `optional = false`: la relación es obligatoria (`NOT NULL`).
- `@JoinColumn(name = "tecnico_id")`: el nombre de la columna que guarda el id del técnico (la **clave foránea**).
- `@ToString.Exclude`: le dice a Lombok que **no** incluya este campo en `toString()`. Si no, podría intentar cargar la relación o entrar en un bucle infinito.
- **¿Por qué no `@ManyToMany`?** Los lineamientos dicen que conviene una **entidad intermedia** cuando hay que manipular la relación. Así controlamos exactamente qué se guarda, y la tabla coincide con el DER (`TECNICO_CERTIFICACION`). Solo modelamos el lado N→1, que es el que se navega (lineamiento §4: "empezar por N→1").

`TecnicoZona` es la misma idea, para las zonas.

---

## Los DTOs

### `dtos/TecnicoRequest.java`

```java
public record TecnicoRequest(
        @NotBlank(message = "El legajo es obligatorio") @Size(max = 20) String legajo,
        ...
        @Schema(example = "true", description = "Si se omite, el técnico queda disponible")
        Boolean disponible,

        @Schema(example = "[1, 2]") @NotEmpty(message = "Debe tener al menos una certificación")
        List<@NotNull Long> certificacionIds,

        @Schema(example = "[1]")
        List<@NotNull Long> zonaIds
) {
}
```
- Igual que `ClienteRequest`, más:
  - `disponible` sin validación: es opcional (si no viene, `true`);
  - `@NotEmpty List<@NotNull Long> certificacionIds`: la lista no puede estar vacía, y ninguno de sus elementos puede ser `null`. Un técnico sin certificaciones no sirve para nada;
  - `zonaIds`: opcional.

### `dtos/TecnicoResponse.java`, `CatalogoResponse.java`, `CandidatoResponse.java` y `DisponibilidadRequest.java`

```java
public record TecnicoResponse(Long id, String legajo, String nombre, String apellido, String dni,
        Double latitud, Double longitud, Boolean disponible,
        List<CatalogoResponse> certificaciones, List<CatalogoResponse> zonas) { }

public record CatalogoResponse(Long id, String nombre) { }

public record CandidatoResponse(Long tecnicoId, String nombre, String apellido,
        Double latitud, Double longitud, Double distanciaKm) { }

public record DisponibilidadRequest(@NotNull(message = "Indicá si el técnico está disponible") Boolean disponible) { }
```
- `TecnicoResponse`: el perfil completo, con las certificaciones y las zonas como listas de `{id, nombre}`.
- `CatalogoResponse`: un elemento de catálogo. Se usa tanto para certificaciones como para zonas.
- `CandidatoResponse`: un técnico candidato **con su distancia** al cliente.
- `DisponibilidadRequest`: el cuerpo del `PATCH` para prender o apagar la disponibilidad.

---

## Los repositorios

```java
public interface TecnicoRepository extends JpaRepository<Tecnico, Long> {
    boolean existsByDni(String dni);
    boolean existsByLegajo(String legajo);
    Page<Tecnico> findByDisponible(Boolean disponible, Pageable pageable);
}
```
- Consultas por nombre (ver ms-clientes). `findByDisponible` → `WHERE disponible = ?`, paginado.

`CertificacionRepository` y `ZonaCoberturaRepository` no agregan nada: usan lo que trae `JpaRepository`.

```java
public interface TecnicoCertificacionRepository extends JpaRepository<TecnicoCertificacion, Long> {

    @Query("select tc from TecnicoCertificacion tc join fetch tc.certificacion where tc.tecnico.id in :tecnicoIds")
    List<TecnicoCertificacion> findConCertificacionByTecnicoIdIn(@Param("tecnicoIds") Collection<Long> tecnicoIds);

    @Query("select tc.tecnico from TecnicoCertificacion tc "
            + "where tc.certificacion.id = :certificacionId and tc.tecnico.disponible = true")
    List<Tecnico> findTecnicosDisponiblesConCertificacion(@Param("certificacionId") Long certificacionId);
}
```
- `@Query("...")`: cuando el nombre del método no alcanza, se escribe la consulta en **JPQL**, un SQL que habla de **clases y atributos** en vez de tablas y columnas.
- **Primera consulta** (`findConCertificacionByTecnicoIdIn`):
  - "dame las filas `TecnicoCertificacion` de estos técnicos (`in :tecnicoIds`)";
  - `join fetch tc.certificacion`: "y traé la certificación **en la misma consulta**". Así se evita el **problema N+1**: listar 20 técnicos haría 1 consulta para la lista + 20 para sus certificaciones; con esto es **1 sola**.
  - `:tecnicoIds` es un parámetro con nombre, que `@Param("tecnicoIds")` conecta con el argumento del método.
- **Segunda consulta** (`findTecnicosDisponiblesConCertificacion`): "dame los **técnicos** que tienen la certificación X y están disponibles". Es el primer filtro de la búsqueda de candidatos (RN-2).

`TecnicoZonaRepository` tiene la consulta equivalente para zonas.

---

## `services/TecnicoService.java`: el cocinero

### Constructor

```java
    public TecnicoService(TecnicoRepository tecnicoRepository,
                          CertificacionRepository certificacionRepository,
                          ZonaCoberturaRepository zonaRepository,
                          TecnicoCertificacionRepository tecnicoCertificacionRepository,
                          TecnicoZonaRepository tecnicoZonaRepository,
                          @Value("${app.candidatos.radio-km:13.0}") double radioKm) {
        ...
        this.radioKm = radioKm;
    }
```
- Recibe los 5 repositorios y el **radio de búsqueda** desde la configuración (13 km por defecto). Así se puede cambiar sin tocar el código.

### Crear un técnico

```java
    @Transactional
    public TecnicoResponse crear(TecnicoRequest request) {
        if (tecnicoRepository.existsByDni(request.dni())) {
            throw new ReglaNegocioException("Ya existe un técnico con DNI " + request.dni());
        }
        if (tecnicoRepository.existsByLegajo(request.legajo())) {
            throw new ReglaNegocioException("Ya existe un técnico con legajo " + request.legajo());
        }
```
- Dos reglas: DNI y legajo únicos → si se repiten, **422** (EN-10).

```java
        List<Certificacion> certificaciones = buscarCertificaciones(request.certificacionIds());
        List<ZonaCobertura> zonas = buscarZonas(request.zonaIds() == null ? List.of() : request.zonaIds());
```
- Busca las certificaciones y las zonas pedidas. Si alguna no existe, esos métodos lanzan un 422 (ver más abajo).
- `request.zonaIds() == null ? List.of() : ...`: si no mandaron zonas, usa una lista vacía.

```java
        Tecnico tecnico = tecnicoRepository.save(Tecnico.builder()
                .legajo(request.legajo())
                ...
                .disponible(request.disponible() == null || request.disponible())
                .build());
```
- Arma el técnico con el **builder** de Lombok y lo guarda.
- `request.disponible() == null || request.disponible()`: si no lo mandaron (`null`) → `true`; si lo mandaron → su valor.

```java
        certificaciones.forEach(c -> tecnicoCertificacionRepository.save(
                TecnicoCertificacion.builder().tecnico(tecnico).certificacion(c).build()));
        zonas.forEach(z -> tecnicoZonaRepository.save(
                TecnicoZona.builder().tecnico(tecnico).zona(z).build()));
```
- Por cada certificación, guarda una fila en la tabla del medio: *"este técnico tiene esta certificación"*. Lo mismo con las zonas.
- Todo dentro de la misma transacción: si algo falla, no queda un técnico a medias.

```java
        log.info("Técnico creado id={} legajo={} con {} certificaciones", tecnico.getId(), tecnico.getLegajo(),
                certificaciones.size());
        return aResponse(tecnico,
                certificaciones.stream().map(c -> new CatalogoResponse(c.getId(), c.getNombre())).toList(),
                zonas.stream().map(z -> new CatalogoResponse(z.getId(), z.getNombre())).toList());
    }
```
- Loguea el evento y arma la respuesta convirtiendo cada certificación y cada zona en `CatalogoResponse`.

### Listar (sin el problema N+1)

```java
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
```
1. Trae **una página** de técnicos (filtrando por disponibilidad si se pidió).
2. Junta los **ids** de esa página.
3. Con **una consulta** trae todas las certificaciones de esos técnicos, y con **otra** todas las zonas, en un `Map` (técnico → lista).
4. Arma cada `TecnicoResponse` buscando en los mapas. `getOrDefault(id, List.of())`: si un técnico no tiene zonas, devuelve una lista vacía.

En total son **3 consultas**, se pidan 3 o 100 técnicos.

```java
    private Map<Long, List<CatalogoResponse>> certificacionesPorTecnico(Collection<Long> tecnicoIds) {
        if (tecnicoIds.isEmpty()) {
            return Map.of();
        }
        return tecnicoCertificacionRepository.findConCertificacionByTecnicoIdIn(tecnicoIds).stream()
                .collect(Collectors.groupingBy(tc -> tc.getTecnico().getId(),
                        Collectors.mapping(tc -> new CatalogoResponse(tc.getCertificacion().getId(),
                                tc.getCertificacion().getNombre()), Collectors.toList())));
    }
```
- Si no hay ids, no consulta: `IN ()` vacío es inválido en SQL.
- `Collectors.groupingBy(clave, ...)`: **agrupa** las filas por id de técnico. Es como separar figuritas por equipo.
- `Collectors.mapping(..., toList())`: dentro de cada grupo, convierte cada fila en un `CatalogoResponse` y las junta en una lista.
- Resultado: `{1 → [Redes, Impresoras], 2 → [Impresoras, Notebooks Apple], ...}`.

### Obtener y cambiar disponibilidad

```java
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
```
- `obtener`: reutiliza los mismos ayudantes con una lista de un solo id.
- `cambiarDisponibilidad`: cambia el campo y listo; Hibernate hace el `UPDATE` solo al terminar la transacción (*dirty checking*, ver ms-clientes).

### ⭐ Buscar candidatos (RN-2 y RN-9)

```java
    @Transactional(readOnly = true)
    public List<CandidatoResponse> buscarCandidatos(Long certificacionId, double latitud, double longitud) {
        if (!certificacionRepository.existsById(certificacionId)) {
            throw new RecursoNoEncontradoException("No existe la certificación con id " + certificacionId);
        }
```
- Si la certificación pedida no existe → **404**.

```java
        List<CandidatoResponse> candidatos = tecnicoCertificacionRepository
                .findTecnicosDisponiblesConCertificacion(certificacionId).stream()
```
- **Filtro 1 (en la base de datos):** técnicos disponibles con esa certificación.

```java
                .map(t -> new CandidatoResponse(t.getId(), t.getNombre(), t.getApellido(),
                        t.getLatitud(), t.getLongitud(),
                        Haversine.distanciaKm(latitud, longitud, t.getLatitud(), t.getLongitud())))
```
- A cada uno le calcula la **distancia** al cliente con **Haversine**, de la librería commons-geo. Es un cálculo local e instantáneo: no llama a OSRM por cada técnico.

```java
                .filter(c -> c.distanciaKm() < radioKm)
```
- **Filtro 2 (en Java):** solo los que están a **menos de 13 km**. Acá queda afuera "Lejano López", que está en Carlos Paz.

```java
                .sorted(Comparator.comparing(CandidatoResponse::distanciaKm))
                .toList();
```
- Los **ordena** del más cercano al más lejano. El primero de la lista es "el más cercano", el que elige la oferta automática.

```java
        log.info("Búsqueda de candidatos certificacion={} -> {} técnicos a menos de {} km",
                certificacionId, candidatos.size(), radioKm);
        return candidatos;
    }
```

### Ayudantes de validación

```java
    private List<Certificacion> buscarCertificaciones(Collection<Long> ids) {
        Collection<Long> unicos = new LinkedHashSet<>(ids);
        List<Certificacion> encontradas = certificacionRepository.findAllById(unicos);
        if (encontradas.size() != unicos.size()) {
            throw new ReglaNegocioException("Alguna certificación no existe: " + faltantes(unicos,
                    encontradas.stream().map(Certificacion::getId).toList()));
        }
        return encontradas;
    }
```
- `new LinkedHashSet<>(ids)`: saca los repetidos (si mandaron `[1, 1, 2]` queda `[1, 2]`).
- `findAllById`: busca todas de una vez.
- Si se encontraron menos de las pedidas, alguna no existe → **422**, diciendo cuáles faltan.

```java
    private static Collection<Long> faltantes(Collection<Long> pedidos, Collection<Long> encontrados) {
        Collection<Long> resultado = new HashSet<>(pedidos);
        resultado.removeAll(encontrados);
        return resultado;
    }
```
- "Pedidos menos encontrados" = los que faltan.

---

## `services/CatalogoService.java`

```java
    @Transactional(readOnly = true)
    public List<CatalogoResponse> listarCertificaciones() {
        return certificacionRepository.findAll(Sort.by("id")).stream()
                .map(c -> new CatalogoResponse(c.getId(), c.getNombre())).toList();
    }
```
- Devuelve todas las certificaciones ordenadas por id. Es un catálogo de 3 filas que casi no cambia; por eso no se pagina (lo aclara el comentario de la clase).
- `listarZonas()` hace lo mismo con las zonas.

---

## `controllers/TecnicoController.java`

```java
@RestController
@RequestMapping("/api/v1/tecnicos")
@Tag(name = "Técnicos", description = "Padrón de técnicos y búsqueda de candidatos (F3, F5)")
public class TecnicoController {
```

| Método | Ruta | Rol (`@PreAuthorize`) | Qué hace |
|---|---|---|---|
| `crear` | `POST /api/v1/tecnicos` | ADMINISTRADOR | Alta (201 + Location) |
| `listar` | `GET /api/v1/tecnicos?disponible=&page=&size=` | todos + SISTEMA | Página de técnicos |
| `candidatos` | `GET /api/v1/tecnicos/candidatos?certificacionId=&latitud=&longitud=` | todos + SISTEMA | Búsqueda de candidatos |
| `obtener` | `GET /api/v1/tecnicos/{tecnicoId}` | todos + SISTEMA | Perfil |
| `cambiarDisponibilidad` | `PATCH /api/v1/tecnicos/{tecnicoId}/disponibilidad` | ADMINISTRADOR, TECNICO | Prender o apagar disponibilidad |

"Todos + SISTEMA" = `hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO','SISTEMA')`. `SISTEMA` es la cuenta de servicio con la que ms-solicitudes consulta candidatos desde el scheduler (sin usuario).

Lo nuevo respecto de ms-clientes:

```java
    @GetMapping("/candidatos")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO','SISTEMA')")
    public ResponseEntity<List<CandidatoResponse>> candidatos(
            @Parameter(description = "Id de la certificación requerida", example = "1")
            @RequestParam Long certificacionId,
            @Parameter(description = "Latitud del cliente", example = "-31.420")
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") Double latitud,
            @Parameter(description = "Longitud del cliente", example = "-64.188")
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") Double longitud) {
        return ResponseEntity.ok(tecnicoService.buscarCandidatos(certificacionId, latitud, longitud));
    }
```
- `@RequestParam` sin `required = false`: los tres parámetros son **obligatorios**. Si falta uno → 400.
- `@DecimalMin` y `@DecimalMax` directamente sobre el parámetro: Spring valida el rango (**validación de métodos**). Si mandás `latitud=-131` → 400, con el detalle armado por `handleHandlerMethodValidationException`.
- **¿Por qué `/candidatos` no choca con `/{tecnicoId}`?** Spring prefiere la ruta **literal** (`/candidatos`) antes que la variable (`/{tecnicoId}`).

```java
    @PatchMapping("/{tecnicoId}/disponibilidad")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','TECNICO')")
    public ResponseEntity<TecnicoResponse> cambiarDisponibilidad(@PathVariable Long tecnicoId,
                                                                 @Valid @RequestBody DisponibilidadRequest request) {
        return ResponseEntity.ok(tecnicoService.cambiarDisponibilidad(tecnicoId, request.disponible()));
    }
```
- `@PatchMapping`: **PATCH** = modificación **parcial** (solo un campo), como piden los lineamientos (§3).

## `controllers/CatalogoController.java`

```java
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catálogos", description = "Certificaciones y zonas de cobertura")
public class CatalogoController {
    @GetMapping("/certificaciones") ... listarCertificaciones()
    @GetMapping("/zonas")           ... listarZonas()
}
```
- Dos recursos propios (`/api/v1/certificaciones` y `/api/v1/zonas`), que también se exponen por el gateway.

---

## `config/DataLoader.java`: los datos del PDF

```java
        Certificacion redes = certificacionRepository.save(Certificacion.builder().nombre("Redes").build());
        Certificacion impresoras = ...("Impresoras")
        Certificacion notebooks = ...("Notebooks Apple")

        ZonaCobertura centro = ...("Centro")
        ZonaCobertura nuevaCordoba = ...("Nueva Córdoba")

        Tecnico juan = tecnico("T-001", "Juan", "Pérez", "25111222", -31.4167, -64.1833);
        Tecnico ana = tecnico("T-002", "Ana", "Gómez", "26222333", -31.4260, -64.1900);
        Tecnico lejano = tecnico("T-003", "Lejano", "López", "27333444", -31.4241, -64.4978);

        asignar(juan, redes, centro);
        asignar(juan, impresoras, null);
        asignar(ana, impresoras, nuevaCordoba);
        asignar(ana, notebooks, null);
        asignar(lejano, redes, null);
```
- Igual que en ms-clientes, solo carga si la base está vacía.
- Los datos están elegidos para que las demos den siempre lo mismo. Distancias al cliente 1 (Carlos, en `-31.420, -64.188`):

| Técnico | Certificaciones | Distancia a Carlos | ¿Candidato para Redes? | ¿Para Impresoras? |
|---|---|---|---|---|
| Juan Pérez (id 1) | Redes, Impresoras | 0.58 km | ✅ | ✅ (1º) |
| Ana Gómez (id 2) | Impresoras, Notebooks Apple | 0.69 km | ❌ no sabe Redes | ✅ (2º) |
| Lejano López (id 3) | Redes | ~29 km (Carlos Paz) | ❌ más de 13 km | ❌ |

- `tecnico(...)` y `asignar(...)` son métodos privados que evitan repetir el builder y los `save`.
- `asignar(juan, impresoras, null)`: el `null` en la zona significa "esta certificación, sin agregar zona".
