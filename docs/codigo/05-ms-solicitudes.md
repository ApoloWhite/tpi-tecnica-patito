# 05 – ms-solicitudes: el cerebro

## ¿Para qué sirve? (explicado fácil)

Es la **sala de control**. Recibe los pedidos de arreglo (las **solicitudes**) y los lleva de la mano, paso por paso, hasta que el trabajo termina:

```
PENDIENTE → OFERTADA → ASIGNADA → EN_CURSO → FINALIZADA
```

Para eso:
- **llama por teléfono** a las otras salas: a ms-clientes para saber dónde vive el cliente y a ms-tecnicos para buscar técnicos cercanos;
- **ofrece el trabajo** al técnico más cercano, solo o a mano;
- tiene un **reloj** (el scheduler) que vence las ofertas no contestadas;
- **calcula cuánto cuesta** la visita, con 4 formas de cobrar (patrón Strategy);
- **anota todo** en un historial.

> 📌 `SecurityConfig`, `CorrelationIdFilter`, `OpenApiConfig`, `RecursoNoEncontradoException` y `ReglaNegocioException` son iguales a las de ms-clientes ([03-ms-clientes.md](03-ms-clientes.md)). `GlobalExceptionHandler` también, con dos manejadores más (ver la sección 8).

```
ms-solicitudes/src/main/java/utnfc/isi/back/tpi/solicitudes/
├── MsSolicitudesApplication.java       ← 1
├── models/      EstadoSolicitud, ModoAsignacion, Prioridad, TipoServicio, Solicitud, HistorialEstado   ← 2
├── dtos/        SolicitudRequest/Response, HistorialResponse, ElegirRequest/Response, OpcionTecnicoResponse,
│                PresupuestoRequest, ClienteDto, TecnicoDto, CandidatoDto                                  ← 3
├── repositories/ SolicitudRepository, HistorialEstadoRepository                                          ← 4
├── config/      RestClientConfig (+ los comunes)                                                         ← 5
├── clients/     ClientesClient, TecnicosClient, RemoteErrors                                             ← 6
├── services/costos/ Tarifas, ContextoCosto, DetalleCosto, EstrategiaCosto, Estrategia×4, CalculadoraCosto ← 7
├── exceptions/  + ServicioNoDisponibleException, ServicioRemotoException                                 ← 8
├── services/    SolicitudService, OfertaScheduler                                                        ← 9 y 10
└── controllers/ SolicitudController                                                                      ← 11
```

---

## 1. `MsSolicitudesApplication.java`

```java
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class MsSolicitudesApplication { ... main ... }
```
Igual que ms-clientes, más dos anotaciones:
- `@EnableScheduling`: **enciende el reloj**. Sin esto, los métodos `@Scheduled` (el que vence las ofertas) nunca se ejecutarían.
- `@ConfigurationPropertiesScan`: busca clases con `@ConfigurationProperties` (como `Tarifas`) y las llena con los valores del `application.yml`.

---

## 2. Los modelos

### `models/EstadoSolicitud.java`: el mapa de estados

```java
public enum EstadoSolicitud {
    PENDIENTE, OFERTADA, ASIGNADA, EN_CURSO, REPROGRAMADA, DERIVADA, CANCELADA, FINALIZADA;
```
- Un **enum** con los 8 estados posibles (RN-7). Una solicitud siempre está en **uno solo**.

```java
    public static final Set<EstadoSolicitud> EN_ESPERA = EnumSet.of(OFERTADA, ASIGNADA, REPROGRAMADA);
```
- Los estados que "ocupan un lugar en la agenda" del técnico. Se usan para la regla RN-5 (máximo 2 en espera).
- `EnumSet.of(...)`: un conjunto rápido y liviano, especial para enums.

```java
    private static final Map<EstadoSolicitud, Set<EstadoSolicitud>> TRANSICIONES = new EnumMap<>(EstadoSolicitud.class);

    static {
        TRANSICIONES.put(PENDIENTE, EnumSet.of(OFERTADA, CANCELADA));
        TRANSICIONES.put(OFERTADA, EnumSet.of(ASIGNADA, PENDIENTE, CANCELADA));
        TRANSICIONES.put(ASIGNADA, EnumSet.of(EN_CURSO, CANCELADA));
        TRANSICIONES.put(EN_CURSO, EnumSet.of(FINALIZADA, REPROGRAMADA, DERIVADA));
        TRANSICIONES.put(REPROGRAMADA, EnumSet.of(EN_CURSO));
        TRANSICIONES.put(DERIVADA, EnumSet.of(PENDIENTE));
        TRANSICIONES.put(CANCELADA, EnumSet.noneOf(EstadoSolicitud.class));
        TRANSICIONES.put(FINALIZADA, EnumSet.noneOf(EstadoSolicitud.class));
    }
```
- El **mapa de caminos permitidos**: para cada estado, a qué estados se puede ir. Es como un tablero de juego: desde PENDIENTE solo podés moverte a OFERTADA o CANCELADA.
- `static { ... }`: un **bloque estático**; se ejecuta una sola vez, cuando Java carga la clase.
- `EnumSet.noneOf(...)`: un conjunto vacío. CANCELADA y FINALIZADA son **finales**: no se sale de ahí.

```java
    public boolean puedePasarA(EstadoSolicitud destino) {
        return TRANSICIONES.get(this).contains(destino);
    }
}
```
- `this` es el estado actual. `estado.puedePasarA(EN_CURSO)` responde `true` o `false`. Así nadie puede "saltearse" estados (RN-7).

### `models/ModoAsignacion.java`, `Prioridad.java` y `TipoServicio.java`

```java
public enum ModoAsignacion { AUTOMATICA, MANUAL }
public enum Prioridad { NORMAL, URGENTE }
public enum TipoServicio { REPARACION, INSTALACION, MANTENIMIENTO }
```
- `ModoAsignacion`: si el sistema elige al técnico solo (`AUTOMATICA`) o lo elige una persona (`MANUAL`).
- `Prioridad`: `URGENTE` cobra un recargo.
- `TipoServicio`: las tres categorías de RN-1; una solicitud puede tener una, dos o las tres.

### `models/Solicitud.java`: la ficha del pedido

```java
@Entity
@Table(name = "solicitud")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Solicitud {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;
```
- `@Version`: el **control de concurrencia optimista**. Cada vez que se guarda la solicitud, Hibernate suma 1 a `version` y agrega al `UPDATE` la condición `WHERE version = <la que leí>`. Si otra operación la cambió en el medio, ese `UPDATE` no encuentra la fila y falla, en vez de pisar el cambio del otro.
  - Ejemplo: el técnico acepta **justo** cuando el reloj vence la oferta. Gana el primero que guarda; el segundo recibe un error de concurrencia. Así nunca quedan dos técnicos asignados (RN-4).

```java
    // --- Snapshot del cliente ---
    @Column(nullable = false)
    private Long clienteId;
    @Column(nullable = false, length = 170)
    private String clienteNombre;
    @Column(nullable = false, length = 210)
    private String domicilio;
    @Column(nullable = false)
    private Double latitud;
    @Column(nullable = false)
    private Double longitud;
```
- El **snapshot** (la "foto"): se **copian** los datos del cliente en el momento de crear la solicitud. Si mañana Carlos se muda, la solicitud de hoy sigue diciendo adónde había que ir hoy.
- Se guarda `clienteId` (un número), **no** una relación JPA con `Cliente`: el cliente vive en **otra base**, en otro microservicio. Cada servicio tiene su propia base de datos.

```java
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "solicitud_tipo", joinColumns = @JoinColumn(name = "solicitud_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_servicio", nullable = false, length = 20)
    @ToString.Exclude
    @Builder.Default
    private Set<TipoServicio> tiposServicio = new HashSet<>();
```
- `@ElementCollection`: una **lista de valores simples** que se guarda en otra tabla (`solicitud_tipo`, la del DER). No es una entidad aparte: solo hay `solicitud_id` + `tipo_servicio`.
- `@Enumerated(EnumType.STRING)`: guarda el enum como **texto** (`"REPARACION"`) y no como número (0, 1, 2). Si mañana se agrega un tipo nuevo en el medio, los datos viejos no se desordenan.
- `@Builder.Default`: con `@Builder`, si no se asigna, el campo arrancaría en `null`. Esto le dice que arranque con `new HashSet<>()`.
- `Set`: un conjunto, no admite repetidos.

```java
    @Column(nullable = false, length = 60)
    private String marca;
    ... modelo, descripcion, especialidadId ...
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoSolicitud estado;
    ... prioridad, fechaHoraProgramada, esFeriado ...

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ModoAsignacion modoAsignacion;
```
- Los datos del pedido. `especialidadId` es el id de la certificación requerida (otra vez un número, no una relación).
- `modoAsignacion` **no** tiene `nullable = false` a propósito: la columna se agregó después, y en una base H2 vieja las filas existentes quedarían en `null` y Hibernate no podría crear la columna. `null` se interpreta como MANUAL.

```java
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "solicitud_tecnico_descartado", joinColumns = @JoinColumn(name = "solicitud_id"))
    @Column(name = "tecnico_id", nullable = false)
    @ToString.Exclude
    @Builder.Default
    private Set<Long> tecnicosDescartados = new HashSet<>();
```
- La "lista negra" de esta solicitud: los técnicos que la **rechazaron**, la dejaron **vencer** o la **derivaron**. La oferta automática nunca se la vuelve a ofrecer a ellos.

```java
    // --- Snapshot del técnico y oferta ---
    private Long tecnicoId;
    @Column(length = 170)
    private String tecnicoNombre;
    private LocalDateTime ofertaExpira;

    // --- Costo de visita (RN-8, RN-11) ---
    private Double distanciaKm;
    private Integer tiempoEstimadoMin;
    @Column(length = 10)
    private String fuenteDistancia;
    @Column(length = 20)
    private String estrategiaCosto;
    @Column(precision = 12, scale = 2)
    private BigDecimal costoBase;
    @Column(precision = 12, scale = 2)
    private BigDecimal recargo;
    @Column(precision = 12, scale = 2)
    private BigDecimal total;

    // --- Diagnóstico y presupuesto (F7) ---
    @Column(length = 500)
    private String diagnostico;
    @Column(precision = 12, scale = 2)
    private BigDecimal presupuesto;

    @Column(nullable = false)
    private LocalDateTime fechaCreacion;
```
- `tecnicoId` y `tecnicoNombre`: quién tiene la oferta o el trabajo ahora. Son `null` mientras está PENDIENTE.
- `ofertaExpira`: hasta cuándo puede contestar el técnico (ahora + 15 min).
- El **detalle del costo** se guarda completo (distancia, fuente, estrategia, base, recargo y total) para poder **auditarlo** después.
- **Plata con `BigDecimal`, nunca con `Double`** (lineamiento §4). `Double` tiene errores de redondeo: `0.1 + 0.2 = 0.30000000000000004`. `BigDecimal` es exacto.
- `precision = 12, scale = 2`: hasta 12 dígitos en total, 2 de ellos decimales (centavos).

```java
    public boolean esAutomatica() {
        return modoAsignacion == ModoAsignacion.AUTOMATICA;
    }
}
```
- Un método de ayuda: hace que el service se lea como castellano (`if (solicitud.esAutomatica())`).

### `models/HistorialEstado.java`: el diario de la solicitud

```java
@Entity
@Table(name = "historial_estado")
...
public class HistorialEstado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solicitud_id")
    @ToString.Exclude
    private Solicitud solicitud;

    @Enumerated(EnumType.STRING) @Column(length = 20)
    private EstadoSolicitud estadoAnterior;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EstadoSolicitud estadoNuevo;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    @Column(length = 500)
    private String observaciones;

    @Column(nullable = false, length = 80)
    private String usuario;
}
```
- Una fila por **cada cambio** (F8, RN-7). Nunca se modifica ni se borra: es una auditoría.
- `@ManyToOne` a `Solicitud`: muchos registros de historial por cada solicitud (N→1, LAZY).
- `estadoAnterior` puede ser `null`: el primer registro es la creación (`null → PENDIENTE`).
- `fecha_hora`: se llama así porque `timestamp` es una palabra reservada de SQL.
- `usuario`: quién hizo el cambio (`cliente1`, `tecnico1`) o `sistema` si lo hizo el sistema solo.

---

## 3. Los DTOs

### `dtos/SolicitudRequest.java`

```java
public record SolicitudRequest(
        @NotNull(message = "El clienteId es obligatorio") Long clienteId,
        @NotEmpty(message = "Debe indicar al menos un tipo de servicio") Set<@NotNull TipoServicio> tiposServicio,
        @NotBlank(message = "La marca es obligatoria") @Size(max = 60) String marca,
        @NotBlank(message = "El modelo es obligatorio") @Size(max = 60) String modelo,
        @NotBlank(message = "La descripción es obligatoria") @Size(max = 500) String descripcion,
        @NotNull(message = "El especialidadId es obligatorio") Long especialidadId,
        @NotNull(message = "La prioridad es obligatoria") Prioridad prioridad,
        @NotNull(message = "La fecha y hora programada es obligatoria") LocalDateTime fechaHoraProgramada,
        Boolean esFeriado,
        ModoAsignacion modoAsignacion
) { }
```
- **RN-1 en código:** marca, modelo, descripción y **al menos un** tipo de servicio son obligatorios. Si falta algo → **400** (EN-04).
- Si mandan un tipo que no existe (`"LAVADO"`), Jackson no lo puede convertir al enum → 400.
- `LocalDateTime`: fecha y hora **sin zona horaria**, en formato `"2026-12-01T10:00:00"`.
- `esFeriado` y `modoAsignacion` son opcionales: si no vienen, se toman como `false` y `AUTOMATICA`.

### Los demás DTOs

| DTO | Para qué |
|---|---|
| `SolicitudResponse` | Todo el detalle de la solicitud. `desde(Solicitud s)` copia los datos; si `modoAsignacion` es `null` lo muestra como `MANUAL`; los conjuntos se devuelven **ordenados** (`.stream().sorted().toList()`) para que la respuesta sea siempre igual |
| `HistorialResponse` | Un registro del historial |
| `ElegirRequest` | Los datos para simular: especialidad, coordenadas, prioridad y fecha |
| `ElegirResponse` | `cantidad`, `recomendado` (el más cercano), `candidatos` (todos) y un `mensaje` |
| `OpcionTecnicoResponse` | Un candidato con su distancia, minutos y costo estimado |
| `PresupuestoRequest` | `diagnostico` (`@NotBlank`) e `importe` (`BigDecimal`, `@Positive` = mayor a cero, `@Digits(integer = 10, fraction = 2)` = hasta 2 decimales) |
| `ClienteDto`, `TecnicoDto`, `CandidatoDto` | Lo que **devuelven los otros microservicios**. Llevan `@JsonIgnoreProperties(ignoreUnknown = true)` para que, si el otro servicio agrega campos nuevos, no se rompa nada. Es una copia propia: los microservicios **no comparten clases** (bajo acoplamiento) |

---

## 4. Los repositorios

```java
public interface SolicitudRepository extends JpaRepository<Solicitud, Long> {

    Page<Solicitud> findByEstado(EstadoSolicitud estado, Pageable pageable);

    List<Solicitud> findByEstadoAndOfertaExpiraBefore(EstadoSolicitud estado, LocalDateTime limite);

    long countByTecnicoIdAndEstadoIn(Long tecnicoId, Collection<EstadoSolicitud> estados);

    long countByTecnicoIdAndEstado(Long tecnicoId, EstadoSolicitud estado);
}
```
- `findByEstado`: el filtro por estado del listado, paginado.
- `findByEstadoAndOfertaExpiraBefore(OFERTADA, ahora)` → `WHERE estado = 'OFERTADA' AND oferta_expira < ahora`: **las ofertas vencidas**. La usa el reloj.
- `countByTecnicoIdAndEstadoIn(tecnicoId, EN_ESPERA)` → `SELECT COUNT(*) ... WHERE tecnico_id = ? AND estado IN (...)`: cuántas solicitudes en espera tiene un técnico (RN-5).
- `countByTecnicoIdAndEstado(tecnicoId, EN_CURSO)`: cuántas tiene en curso (RN-5).

```java
public interface HistorialEstadoRepository extends JpaRepository<HistorialEstado, Long> {
    List<HistorialEstado> findBySolicitudIdOrderByFechaHoraAscIdAsc(Long solicitudId);
}
```
- El historial de una solicitud, del más viejo al más nuevo. `IdAsc` desempata dos registros con la misma hora (la oferta automática se registra en el mismo milisegundo que la creación).

---

## 5. `config/RestClientConfig.java`: los teléfonos hacia las otras salas

**Qué hace:** fabrica un `RestClient` (un "teléfono") por cada microservicio al que llamamos (lineamiento §6). Cada teléfono:
- tiene **marcado el número** (la URL base);
- **corta** si nadie contesta (timeouts);
- **se presenta**: manda el token y el correlation id;
- **anota** cada llamada en el log.

```java
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);
    private static final String CUENTA_SERVICIO = "ms-solicitudes";

    private final OAuth2AuthorizedClientManager cuentaServicio;

    public RestClientConfig(ClientRegistrationRepository registros, OAuth2AuthorizedClientService clientes) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registros, clientes);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        this.cuentaServicio = manager;
    }
```
- La **cuenta de servicio**: la "pulsera de empleado" de ms-solicitudes.
- `ClientRegistrationRepository`: Spring lo arma con lo que dice el `application.yml` en `spring.security.oauth2.client.registration.ms-solicitudes` (client id, secreto y URL de tokens de Keycloak).
- `OAuth2AuthorizedClientService`: donde Spring **guarda** el token obtenido, para no pedir uno nuevo en cada llamada.
- `AuthorizedClientServiceOAuth2AuthorizedClientManager`: el "gestor" que pide el token a Keycloak cuando hace falta. Esta versión sirve **fuera de un pedido web** (en el scheduler no hay pedido HTTP).
- `.clientCredentials()`: el tipo de pedido es `client_credentials`: "soy la aplicación ms-solicitudes, este es mi secreto, dame un token **para mí**".

```java
    @Bean
    public RestClient clientesRestClient(RestClient.Builder builder,
                                         @Value("${app.clientes.base-url}") String baseUrl,
                                         SimpleClientHttpRequestFactory requestFactory) {
        return construir(builder, baseUrl, requestFactory);
    }

    @Bean
    public RestClient tecnicosRestClient(...) { ... igual con app.tecnicos.base-url ... }
```
- Dos beans de tipo `RestClient`, uno por servicio destino. El **nombre del método** es el nombre del bean (`clientesRestClient`), y se usa para elegir el correcto con `@Qualifier`.
- `RestClient.Builder builder`: un armador que nos presta Spring Boot, ya configurado con Jackson y métricas.
- `app.clientes.base-url`: en Docker vale `http://ms-clientes:47383`. Se usa el **nombre del servicio**, nunca `localhost` (lineamiento §10).

```java
    @Bean
    public SimpleClientHttpRequestFactory requestFactory(
            @Value("${app.http.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.http.read-timeout-ms:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }
```
- **Timeouts**: 2 segundos para conectar y 5 para que conteste. Si ms-clientes está caído, no nos quedamos colgados.

```java
    private RestClient construir(RestClient.Builder builder, String baseUrl,
                                 SimpleClientHttpRequestFactory requestFactory) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(propagarContexto())
                .requestInterceptor(registrarLlamada())
                .build();
    }
```
- `builder.clone()`: una **copia** del armador, para que la configuración de un teléfono no se mezcle con la del otro.
- `.requestInterceptor(...)`: un **interceptor** es un paso que se ejecuta en **cada** llamada saliente, justo antes de enviarla. Ponemos dos: uno que agrega token y correlation id y otro que mide y loguea.

```java
    private ClientHttpRequestInterceptor propagarContexto() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
```
- Saca el correlation id de la "mochila" (MDC) y lo pone en el header `X-Correlation-ID` de la llamada. Así ms-clientes registra **el mismo número** (lineamiento §9: "lo propaga en cada llamada saliente").

```java
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
            } else {
                String tokenServicio = tokenCuentaServicio();
                if (tokenServicio != null) {
                    request.getHeaders().setBearerAuth(tokenServicio);
                }
            }
            return execution.execute(request, body);
        };
    }
```
- `SecurityContextHolder.getContext().getAuthentication()`: "¿quién está atendido en este momento?". Spring Security lo guardó al validar el token.
- **Si hay un usuario** (`JwtAuthenticationToken`): se reenvía **su mismo token**. ms-clientes ve que es `cliente1` con rol CLIENTE y aplica sus propias reglas.
- **Si no hay usuario** (el reloj, el scheduler): se usa el token de la **cuenta de servicio** (rol `SISTEMA`).
- `setBearerAuth(token)` = agrega `Authorization: Bearer <token>`.
- `execution.execute(request, body)`: "seguí y mandá el pedido".

```java
    private String tokenCuentaServicio() {
        try {
            OAuth2AuthorizedClient cliente = cuentaServicio.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(CUENTA_SERVICIO).principal(CUENTA_SERVICIO).build());
            return cliente == null ? null : cliente.getAccessToken().getTokenValue();
        } catch (RuntimeException e) {
            log.warn("No se pudo obtener el token de la cuenta de servicio: {}", e.getMessage());
            return null;
        }
    }
```
- `cuentaServicio.authorize(...)`: "dame un token válido para el registro `ms-solicitudes`". Si ya tiene uno guardado y vigente, lo devuelve; si no, se lo pide a Keycloak.
- Si Keycloak no responde, avisa en el log y sigue sin token: el otro servicio va a responder 401 y eso se maneja como un error más.

```java
    private static ClientHttpRequestInterceptor registrarLlamada() {
        return (request, body, execution) -> {
            long inicio = System.currentTimeMillis();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                log.info("Llamada saliente {} {} -> {} ({} ms)", request.getMethod(), request.getURI(),
                        response.getStatusCode().value(), System.currentTimeMillis() - inicio);
                return response;
            } catch (IOException e) {
                log.warn("Llamada saliente {} {} falló tras {} ms: {}", ...);
                throw e;
            }
        };
    }
```
- Mide cuánto tardó cada llamada y la anota: `Llamada saliente GET http://ms-clientes:47383/api/v1/clientes/1 -> 200 (25 ms)`. Lo piden los lineamientos (§6), **sin datos sensibles**: nunca se loguea el token.
- Si la conexión falla (`IOException`: el servicio está caído), lo anota como WARN y **relanza** el error (`throw e`) para que lo maneje Resilience4j.

---

## 6. Los clientes REST (con resiliencia)

### `clients/ClientesClient.java`

```java
@Component
public class ClientesClient {

    private static final Logger log = LoggerFactory.getLogger(ClientesClient.class);

    private final RestClient restClient;

    public ClientesClient(@Qualifier("clientesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }
```
- Una clase `@Component` que **esconde** los detalles HTTP: el service solo llama a `clientesClient.obtener(1)` y recibe un `ClienteDto` (lineamiento §6: "una clase cliente que devuelve DTOs").
- `@Qualifier("clientesRestClient")`: hay **dos** beans `RestClient`; esto elige el de clientes.

```java
    @CircuitBreaker(name = "clientes")
    @Retry(name = "clientes", fallbackMethod = "sinServicio")
    public ClienteDto obtener(Long clienteId) {
```
- **Resilience4j** "envuelve" este método con dos protecciones (su configuración está en el `application.yml`):
  - `@Retry`: si falla por un problema de conexión (`ResourceAccessException`), **reintenta** una vez a los 300 ms. Si sigue fallando, llama al método `sinServicio` (el **fallback**, el plan B);
  - `@CircuitBreaker`: el **disyuntor**. Lleva la cuenta de las últimas 10 llamadas; si fallan la mitad o más, **"salta la térmica"**: durante 20 segundos ni siquiera intenta llamar y falla al instante. Así no se insiste con un servicio que está caído.
- El orden: Retry envuelve a CircuitBreaker (Retry por fuera).

```java
        try {
            return restClient.get()
                    .uri("/api/v1/clientes/{clienteId}", clienteId)
                    .retrieve()
                    .body(ClienteDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontradoException("No existe el cliente con id " + clienteId);
        } catch (HttpClientErrorException e) {
            throw RemoteErrors.traducir("ms-clientes", e);
        }
    }
```
- La llamada: `GET http://ms-clientes:47383/api/v1/clientes/1` → JSON → `ClienteDto`.
- `HttpClientErrorException.NotFound`: ms-clientes respondió **404** → lo traducimos a nuestro `RecursoNoEncontradoException` → nuestro 404 (EN-05).
- Otro error **4xx** (400, 403…) → `RemoteErrors.traducir`: devuelve el mismo código con el mensaje del ProblemDetail remoto (lineamiento §6: "mapear el ProblemDetail remoto").

```java
    @SuppressWarnings("unused")
    private ClienteDto sinServicio(Long clienteId, Throwable causa) {
        RemoteErrors.relanzarSiEsDeNegocio(causa);
        log.warn("ms-clientes no disponible al consultar el cliente {}: {}", clienteId, causa.toString());
        throw new ServicioNoDisponibleException("El servicio de clientes no se encuentra disponible", causa);
    }
}
```
- El **fallback**. Resilience4j lo llama cuando `obtener` falla. Tiene que recibir los **mismos parámetros** más el `Throwable`.
- `@SuppressWarnings("unused")`: el IDE cree que nadie lo usa (lo llama Resilience4j por reflexión), y esto silencia el aviso.
- `relanzarSiEsDeNegocio(causa)`: el fallback recibe **cualquier** error, incluido un 404. Un 404 no significa que el servicio esté caído, así que esos errores se relanzan tal cual.
- Si era un problema de conexión → `ServicioNoDisponibleException` → **503** (EN-09). Siempre se incluye la causa original (lineamiento §5).

### `clients/TecnicosClient.java`

Lo mismo para ms-tecnicos, con dos métodos:

```java
    public TecnicoDto obtener(Long tecnicoId) { ... GET /api/v1/tecnicos/{tecnicoId} ... }

    public List<CandidatoDto> candidatos(Long certificacionId, double latitud, double longitud) {
        try {
            List<CandidatoDto> candidatos = restClient.get()
                    .uri(uri -> uri.path("/api/v1/tecnicos/candidatos")
                            .queryParam("certificacionId", certificacionId)
                            .queryParam("latitud", latitud)
                            .queryParam("longitud", longitud)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CandidatoDto>>() {
                    });
            return candidatos == null ? List.of() : candidatos;
        } ...
    }
```
- `uri(uri -> uri.path(...).queryParam(...).build())`: arma la URL con los parámetros: `/api/v1/tecnicos/candidatos?certificacionId=1&latitud=-31.42&longitud=-64.188`.
- `new ParameterizedTypeReference<List<CandidatoDto>>() {}`: un truco de Java para decirle a Jackson "es una **lista de** `CandidatoDto`". Con `List.class` sola, Java "olvida" de qué es la lista.

### `clients/RemoteErrors.java`

```java
final class RemoteErrors {
    static ServicioRemotoException traducir(String servicio, RestClientResponseException e) {
        return ServicioRemotoException.desde(servicio, e);
    }

    static void relanzarSiEsDeNegocio(Throwable causa) {
        if (causa instanceof RecursoNoEncontradoException
                || causa instanceof ReglaNegocioException
                || causa instanceof ServicioRemotoException) {
            throw (RuntimeException) causa;
        }
    }
}
```
- Ayudantes compartidos por los dos clientes, para no repetir código.
- `final class` sin `public`: solo se puede usar dentro del paquete `clients`.

---

## 7. Los costos: el patrón Strategy

### La idea (explicado fácil)

Un taxi cobra distinto de día, de noche o en feriado. Se podría escribir un `if` gigante… o tener **una "tarjeta de tarifa" por cada situación** y elegir la que corresponde. Eso es el **patrón Strategy**: cada forma de calcular es una clase separada con la misma "forma" (interfaz), y un selector elige cuál usar.

| Estrategia | Cuándo | Recargo |
|---|---|---|
| `ESTANDAR` | NORMAL en horario hábil | 0 % |
| `PRIORIDAD` | URGENTE en horario hábil | +30 % |
| `FUERA_HORARIO` | NORMAL fuera de horario, fin de semana o feriado | +50 % |
| `COMBINADA` | URGENTE y fuera de horario | +80 % (30 + 50) |

**Costo base** = $5000 fijos + $800 × km. **Total** = base + recargo.

### `costos/Tarifas.java`

```java
@ConfigurationProperties(prefix = "tarifas")
public record Tarifas(BigDecimal montoFijo, BigDecimal porKm, BigDecimal recargoPrioridad,
                      BigDecimal recargoFueraHorario, int horaInicio, int horaFin) {
}
```
- `@ConfigurationProperties(prefix = "tarifas")`: Spring llena el record con lo que hay bajo `tarifas:` en el `application.yml`. `monto-fijo` en el yml se convierte solo en `montoFijo` en Java.
- Para cambiar un precio se toca la configuración, no el código.

### `costos/ContextoCosto.java` y `costos/DetalleCosto.java`

```java
public record ContextoCosto(double distanciaKm, boolean urgente, boolean fueraDeHorario) { }
public record DetalleCosto(String estrategia, BigDecimal costoBase, BigDecimal recargo, BigDecimal total) { }
```
- `ContextoCosto`: **lo que entra** a la estrategia (los km y dos preguntas de sí o no).
- `DetalleCosto`: **lo que sale** (qué estrategia se usó y los tres montos).

### `costos/EstrategiaCosto.java`: la interfaz

```java
public interface EstrategiaCosto {

    String nombre();

    boolean aplica(ContextoCosto contexto);

    BigDecimal porcentajeRecargo(Tarifas tarifas);

    default DetalleCosto calcular(ContextoCosto contexto, Tarifas tarifas) {
        BigDecimal base = tarifas.montoFijo()
                .add(tarifas.porKm().multiply(BigDecimal.valueOf(contexto.distanciaKm())))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal recargo = base.multiply(porcentajeRecargo(tarifas)).setScale(2, RoundingMode.HALF_UP);
        return new DetalleCosto(nombre(), base, recargo, base.add(recargo));
    }
}
```
- Cada estrategia tiene que decir: su `nombre()`, si `aplica(...)` en esta situación y cuál es su `porcentajeRecargo(...)`.
- `default ... calcular(...)`: un método **ya escrito** en la interfaz (*default method*), que todas las estrategias heredan. Así la cuenta del costo base está **en un solo lugar**.
- Con `BigDecimal` no se usan `+` ni `*`: se usan `.add(...)` y `.multiply(...)`.
- `.setScale(2, RoundingMode.HALF_UP)`: redondea a 2 decimales "como en la escuela" (5 o más, para arriba).

### Las 4 estrategias

```java
@Component
public class EstrategiaPrioridad implements EstrategiaCosto {

    @Override
    public String nombre() {
        return "PRIORIDAD";
    }

    @Override
    public boolean aplica(ContextoCosto contexto) {
        return contexto.urgente() && !contexto.fueraDeHorario();
    }

    @Override
    public BigDecimal porcentajeRecargo(Tarifas tarifas) {
        return tarifas.recargoPrioridad();
    }
}
```
- `@Component`: cada estrategia es un bean. Spring las encuentra solas.
- `aplica`: **urgente Y NO fuera de horario**. Las 4 condiciones son excluyentes, así siempre aplica exactamente una:

| | dentro de horario | fuera de horario |
|---|---|---|
| **NORMAL** | `EstrategiaEstandar` (`BigDecimal.ZERO`) | `EstrategiaFueraHorario` |
| **URGENTE** | `EstrategiaPrioridad` | `EstrategiaCombinada` (`recargoPrioridad().add(recargoFueraHorario())`) |

### `costos/CalculadoraCosto.java`: el selector

```java
@Service
public class CalculadoraCosto {

    private final List<EstrategiaCosto> estrategias;
    private final Tarifas tarifas;

    public CalculadoraCosto(List<EstrategiaCosto> estrategias, Tarifas tarifas) {
        this.estrategias = estrategias;
        this.tarifas = tarifas;
    }
```
- `List<EstrategiaCosto> estrategias`: si pedís una **lista** de una interfaz, Spring te pasa **todos** los beans que la implementan (las 4). Para agregar una quinta estrategia alcanza con crear otra clase `@Component`; esta clase no se toca. Ese es el beneficio del patrón Strategy.

```java
    public DetalleCosto calcular(double distanciaKm, Prioridad prioridad, LocalDateTime fechaHora, boolean esFeriado) {
        ContextoCosto contexto = new ContextoCosto(distanciaKm, prioridad == Prioridad.URGENTE,
                esFueraDeHorario(fechaHora, esFeriado));
        EstrategiaCosto estrategia = estrategias.stream()
                .filter(e -> e.aplica(contexto))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay estrategia de costo para " + contexto));
        return estrategia.calcular(contexto, tarifas);
    }
```
1. Arma el contexto: los km, si es urgente y si es fuera de horario.
2. Busca la **primera** estrategia que dice "aplico yo".
3. Si ninguna aplica (no debería pasar), es un error de programación → `IllegalStateException` → 500.
4. Le pide a esa estrategia que calcule.

```java
    boolean esFueraDeHorario(LocalDateTime fechaHora, boolean esFeriado) {
        DayOfWeek dia = fechaHora.getDayOfWeek();
        int hora = fechaHora.getHour();
        return esFeriado || dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY
                || hora < tarifas.horaInicio() || hora >= tarifas.horaFin();
    }
}
```
- Fuera de horario = feriado, **o** sábado, **o** domingo, **o** antes de las 8, **o** desde las 18.

---

## 8. Las excepciones nuevas

```java
public class ServicioNoDisponibleException extends RuntimeException {
    public ServicioNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
```
- "Un servicio del que dependo no responde" → **503**. Guarda la **causa** original (el error de conexión), para que el log muestre qué pasó realmente.

```java
public class ServicioRemotoException extends RuntimeException {

    private final int status;
    ...
    public static ServicioRemotoException desde(String servicio, RestClientResponseException e) {
        String detalle = e.getStatusText();
        try {
            ProblemDetail remoto = e.getResponseBodyAs(ProblemDetail.class);
            if (remoto != null && remoto.getDetail() != null) {
                detalle = remoto.getDetail();
            }
        } catch (RuntimeException ignorada) {
            detalle = e.getStatusText();
        }
        return new ServicioRemotoException(e.getStatusCode().value(), servicio + ": " + detalle, e);
    }
```
- "El otro servicio me contestó con un error 4xx". Guarda el **status** (por ejemplo, 403).
- `e.getResponseBodyAs(ProblemDetail.class)`: lee el cuerpo de la respuesta del otro servicio como ProblemDetail y saca su `detail`. Si no se puede leer, usa el texto del status. No se "traga" el error: se reemplaza por un mensaje útil.

En el `GlobalExceptionHandler` de ms-solicitudes se agregan:

```java
    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<Object> manejarServicioCaido(ServicioNoDisponibleException ex) {
        log.error("Dependencia no disponible: {}", ex.getMessage());
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", ex.getMessage());
    }

    @ExceptionHandler(ServicioRemotoException.class)
    public ResponseEntity<Object> manejarServicioRemoto(ServicioRemotoException ex) {
        log.warn("Error devuelto por otro microservicio ({}): {}", ex.getStatus(), ex.getMessage());
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        return problema(status == null ? HttpStatus.BAD_GATEWAY : status, "Error en servicio dependiente",
                ex.getMessage());
    }
```
- Servicio caído → **503**, logueado como **ERROR** (es grave).
- Error remoto → el **mismo código** que devolvió el otro. `HttpStatus.resolve` convierte el número en el enum; si es un código raro, usa 502 (*Bad Gateway*).

---

## 9. `services/SolicitudService.java`: el corazón

### Campos y constructor

```java
@Service
public class SolicitudService {

    private static final Logger log = LoggerFactory.getLogger(SolicitudService.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    static final String USUARIO_SISTEMA = "sistema";
```
- `HORA`: un formateador de fechas para los mensajes del historial (`"22/11 10:56"`).
- `USUARIO_SISTEMA`: el nombre que aparece en el historial cuando el cambio lo hizo el sistema solo.

```java
    public SolicitudService(SolicitudRepository solicitudRepository, HistorialEstadoRepository historialRepository,
                            ClientesClient clientesClient, TecnicosClient tecnicosClient, GeoService geoService,
                            CalculadoraCosto calculadoraCosto,
                            @Value("${app.oferta.ventana-minutos:15}") int ventanaMinutos,
                            @Value("${app.tecnico.max-en-espera:2}") int maxEnEspera,
                            @Value("${app.tecnico.max-en-curso:1}") int maxEnCurso) {
```
- Recibe todo lo que necesita: los 2 repositorios, los 2 clientes REST, el `GeoService` (de commons-geo), la calculadora de costos y tres números de configuración (ventana de 15 minutos y límites de RN-5).

### Consultas

```java
    @Transactional(readOnly = true)
    public Page<SolicitudResponse> listar(EstadoSolicitud estado, Pageable pageable) { ... }

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
```
- `historial` llama a `buscar(id)` primero **solo para validar**: si la solicitud no existe → 404 (en vez de devolver una lista vacía y confundir).
- El DTO se arma **dentro** de la transacción, porque `tiposServicio` es LAZY y se carga en ese momento (y `open-in-view` está apagado).

### F4: crear una solicitud

```java
    @Transactional
    public SolicitudResponse crear(SolicitudRequest request) {
        ClienteDto cliente = clientesClient.obtener(request.clienteId());
```
- **Lo primero** es llamar a ms-clientes. Si el cliente no existe → 404; si ms-clientes está caído → 503. En los dos casos **no se guarda nada** (comprobación 8 del PDF).

```java
        Solicitud solicitud = Solicitud.builder()
                .clienteId(cliente.id())
                .clienteNombre(cliente.nombre() + " " + cliente.apellido())
                .domicilio(cliente.barrio() == null || cliente.barrio().isBlank()
                        ? cliente.calle() : cliente.calle() + ", " + cliente.barrio())
                .latitud(cliente.latitud())
                .longitud(cliente.longitud())
```
- El **snapshot**: copia nombre, domicilio ("Av. Colón 150, Centro") y coordenadas del cliente.

```java
                .tiposServicio(new HashSet<>(request.tiposServicio()))
                .marca(request.marca())
                ...
                .esFeriado(Boolean.TRUE.equals(request.esFeriado()))
                .modoAsignacion(request.modoAsignacion() == null ? ModoAsignacion.AUTOMATICA : request.modoAsignacion())
                .estado(PENDIENTE)
                .fechaCreacion(LocalDateTime.now())
                .build();
```
- `Boolean.TRUE.equals(x)`: devuelve `true` solo si `x` es `true`; si es `null` o `false`, devuelve `false`. Es la forma segura de leer un `Boolean` que puede ser `null`.
- Si no mandaron el modo, es **AUTOMATICA**.
- Arranca en **PENDIENTE**.

```java
        solicitudRepository.save(solicitud);
        registrarHistorial(solicitud, null, PENDIENTE, "Solicitud creada para el cliente " + solicitud.getClienteNombre());
        log.info("Solicitud {} creada para el cliente {} (modo {})", ...);
        if (solicitud.esAutomatica()) {
            ofertarAutomaticamente(solicitud);
        }
        return SolicitudResponse.desde(solicitud);
    }
```
- Guarda la solicitud (ahora tiene `id`), anota `null → PENDIENTE` en el historial y, si es automática, **ofrece el trabajo al técnico más cercano en el mismo momento**.

### F5: simular candidatos (`elegir`)

```java
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
```
1. Pide los candidatos a ms-tecnicos (ya vienen filtrados por certificación y 13 km, y ordenados).
2. **Descarta** los que están al límite de RN-5.
3. A cada uno le calcula el **costo estimado** con la distancia de Haversine.

```java
        if (opciones.isEmpty()) {
            log.warn("ALERTA ADMINISTRADOR: no hay técnicos candidatos para la especialidad {} en ({}, {})", ...);
            return new ElegirResponse(0, null, opciones,
                    "No hay técnicos disponibles: la solicitud queda en espera y se alertó al administrador");
        }
        return new ElegirResponse(opciones.size(), opciones.getFirst(), opciones,
                "Se encontraron " + opciones.size() + " técnicos candidatos");
    }
```
- Sin candidatos: **WARN** en el log (la "alerta al administrador" de RN-2) y respuesta vacía.
- Con candidatos: el **recomendado** es el primero (el más cercano).

### F5/F6: ofertar a mano

```java
    @Transactional
    public SolicitudResponse ofertar(Long id, Long tecnicoId) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, PENDIENTE, "enviar una oferta");
        validarLimiteEnEspera(tecnicoId);
```
1. Busca la solicitud (404 si no existe).
2. Tiene que estar **PENDIENTE** (si no → 422). Esto también garantiza RN-4: si ya está OFERTADA a alguien, no se puede ofertar a otro.
3. **RN-5:** si el técnico ya tiene 2 en espera → 422 (EN-06).

```java
        TecnicoDto tecnico = tecnicosClient.obtener(tecnicoId);
        boolean esCandidato = tecnicosClient
                .candidatos(solicitud.getEspecialidadId(), solicitud.getLatitud(), solicitud.getLongitud())
                .stream().anyMatch(c -> c.tecnicoId().equals(tecnicoId));
        if (!esCandidato) {
            throw new ReglaNegocioException(...);
        }
```
4. Trae al técnico (404 si no existe).
5. Comprueba que **sea candidato** (disponible, con la certificación y a menos de 13 km). `anyMatch` = "¿hay alguno en la lista que cumpla?". Así nadie puede ofertarle a un técnico que no corresponde.

```java
        solicitud.setTecnicoId(tecnico.id());
        solicitud.setTecnicoNombre(tecnico.nombre() + " " + tecnico.apellido());
        solicitud.setOfertaExpira(LocalDateTime.now().plusMinutes(ventanaMinutos));
        transicionar(solicitud, OFERTADA, "Oferta enviada al técnico " + ...);
        return SolicitudResponse.desde(solicitud);
    }
```
6. Anota al técnico (snapshot de su nombre), pone el vencimiento en **ahora + 15 minutos** (RN-3) y pasa a **OFERTADA**.

### F6: el técnico acepta

```java
    @Transactional
    public SolicitudResponse aceptar(Long id, Long tecnicoId) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, OFERTADA, "aceptar la oferta");
        exigirTecnicoOfertado(solicitud, tecnicoId);
        if (solicitud.getOfertaExpira().isBefore(LocalDateTime.now())) {
            throw new ReglaNegocioException("La oferta de la solicitud " + id + " venció a las " + ...);
        }
```
1. Tiene que estar OFERTADA.
2. **RN-4:** solo el técnico al que se le ofreció puede aceptarla.
3. **RN-3:** si ya pasaron los 15 minutos → 422, aunque el reloj todavía no haya pasado a vencerla.

```java
        TecnicoDto tecnico = tecnicosClient.obtener(tecnicoId);
        Ruta ruta = geoService.calcularRuta(tecnico.latitud(), tecnico.longitud(),
                solicitud.getLatitud(), solicitud.getLongitud());
        DetalleCosto costo = calculadoraCosto.calcular(ruta.kilometros(), solicitud.getPrioridad(),
                solicitud.getFechaHoraProgramada(), solicitud.getEsFeriado());
```
4. Busca la base del técnico y le pide a **commons-geo** la ruta real (OSRM, o Haversine si falla). Es el **único** pedido a OSRM de todo el flujo: el del técnico finalista.
5. Calcula el **costo final** con la estrategia que corresponda (RN-8, RN-11).

```java
        solicitud.setDistanciaKm(ruta.kilometros());
        ... (tiempo, fuente, estrategia, base, recargo, total)
        solicitud.setOfertaExpira(null);
        transicionar(solicitud, ASIGNADA, "Técnico " + ... + " aceptó. Costo de visita $" + ...);
        return SolicitudResponse.desde(solicitud);
    }
```
6. Guarda el detalle del costo, borra el vencimiento (ya no hace falta) y pasa a **ASIGNADA**.

### El técnico rechaza

```java
    @Transactional
    public SolicitudResponse rechazar(Long id, Long tecnicoId) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, OFERTADA, "rechazar la oferta");
        exigirTecnicoOfertado(solicitud, tecnicoId);
        String tecnico = solicitud.getTecnicoNombre();
        descartarTecnico(solicitud);
        transicionar(solicitud, PENDIENTE, "Técnico " + tecnico + " rechazó la oferta");
        if (solicitud.esAutomatica()) {
            ofertarAutomaticamente(solicitud);
        }
        return SolicitudResponse.desde(solicitud);
    }
```
- Guarda el nombre **antes** de borrarlo (para el mensaje), lo agrega a la lista de **descartados** y vuelve a **PENDIENTE**.
- **Modo automático:** se la ofrece en el acto al **siguiente** técnico. Por eso en Postman, después de que Juan rechaza, la respuesta ya muestra la oferta a Ana.

### ⭐ La oferta automática

```java
    private void ofertarAutomaticamente(Solicitud solicitud) {
        List<CandidatoDto> candidatos;
        try {
            candidatos = tecnicosClient.candidatos(solicitud.getEspecialidadId(), solicitud.getLatitud(),
                    solicitud.getLongitud());
        } catch (ServicioNoDisponibleException e) {
            log.warn("No se pudo ofertar automáticamente la solicitud {}: {}", ...);
            registrarHistorial(solicitud, PENDIENTE, PENDIENTE, "No se pudo buscar técnicos (MS-Técnicos no disponible). "
                    + "Queda en espera", USUARIO_SISTEMA);
            return;
        }
```
- Pide los candidatos. Si ms-tecnicos está caído, **no falla la creación**: la solicitud queda PENDIENTE con una nota en el historial. Así no se pierde el pedido del cliente.

```java
        Optional<CandidatoDto> elegido = candidatos.stream()
                .filter(c -> !solicitud.getTecnicosDescartados().contains(c.tecnicoId()))
                .filter(c -> !alcanzoLimiteEnEspera(c.tecnicoId()))
                .findFirst();
```
- Recorre los candidatos (ya ordenados por cercanía) y saltea a los **descartados** y a los que están **al límite**.
- `findFirst()`: se queda con **el primero** que pase, o sea, el más cercano disponible. Devuelve un `Optional` (puede estar vacío).

```java
        if (elegido.isEmpty()) {
            log.warn("ALERTA ADMINISTRADOR: la solicitud {} no tiene técnicos candidatos disponibles", ...);
            registrarHistorial(solicitud, PENDIENTE, PENDIENTE, "Sin técnicos candidatos disponibles: queda en espera "
                    + "y se alertó al administrador (RN-2)", USUARIO_SISTEMA);
            return;
        }
```
- No quedó nadie → queda PENDIENTE, alerta en el log y nota en el historial (RN-2).

```java
        CandidatoDto tecnico = elegido.get();
        solicitud.setTecnicoId(tecnico.tecnicoId());
        solicitud.setTecnicoNombre(tecnico.nombre() + " " + tecnico.apellido());
        solicitud.setOfertaExpira(LocalDateTime.now().plusMinutes(ventanaMinutos));
        transicionar(solicitud, OFERTADA, "Oferta automática al técnico más cercano " + ..., USUARIO_SISTEMA);
    }
```
- Le ofrece el trabajo y pasa a **OFERTADA**. En el historial queda el usuario `sistema`: la decisión la tomó el sistema, no la persona.

### El vencimiento (lo llama el reloj)

```java
    @Transactional(readOnly = true)
    public List<Long> ofertasVencidas() {
        return solicitudRepository.findByEstadoAndOfertaExpiraBefore(OFERTADA, LocalDateTime.now()).stream()
                .map(Solicitud::getId).toList();
    }
```
- Devuelve solo los **ids** de las ofertas vencidas.

```java
    @Transactional
    public void expirarOferta(Long id) {
        Solicitud solicitud = buscar(id);
        if (solicitud.getEstado() != OFERTADA || !solicitud.getOfertaExpira().isBefore(LocalDateTime.now())) {
            return;
        }
        String tecnico = solicitud.getTecnicoNombre();
        descartarTecnico(solicitud);
        transicionar(solicitud, PENDIENTE, "Oferta al técnico " + tecnico + " vencida: no respondió en "
                + ventanaMinutos + " min", USUARIO_SISTEMA);
        log.info("Oferta de la solicitud {} vencida, vuelve a PENDIENTE", id);
        if (solicitud.esAutomatica()) {
            ofertarAutomaticamente(solicitud);
        }
    }
```
- Procesa **una** solicitud por transacción. Si falla una, las demás no se ven afectadas.
- Vuelve a leerla y **chequea de nuevo** que siga OFERTADA y vencida: entre que se listaron los ids y este momento, el técnico pudo haber aceptado.
- El técnico que no contestó queda **descartado**, vuelve a PENDIENTE y, si es automática, se ofrece al siguiente.

### F7: presupuesto y aprobación

```java
    @Transactional
    public SolicitudResponse cargarPresupuesto(Long id, PresupuestoRequest request) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, ASIGNADA, "cargar el presupuesto");
        solicitud.setDiagnostico(request.diagnostico());
        solicitud.setPresupuesto(request.importe());
        registrarHistorial(solicitud, ASIGNADA, ASIGNADA, "Técnico cargó diagnóstico y presupuesto por $" + ...);
        return SolicitudResponse.desde(solicitud);
    }
```
- Solo con la solicitud ASIGNADA. **No cambia de estado**, pero queda anotado en el historial (`ASIGNADA → ASIGNADA`).

```java
    @Transactional
    public SolicitudResponse aprobarPresupuesto(Long id) {
        Solicitud solicitud = buscar(id);
        exigirEstado(solicitud, ASIGNADA, "aprobar el presupuesto");
        if (solicitud.getPresupuesto() == null) {
            throw new ReglaNegocioException("La solicitud " + id + " todavía no tiene presupuesto cargado por el técnico (RN-6)");
        }
        long enCurso = solicitudRepository.countByTecnicoIdAndEstado(solicitud.getTecnicoId(), EN_CURSO);
        if (enCurso >= maxEnCurso) {
            throw new ReglaNegocioException("El técnico " + ... + " ya tiene " + enCurso + " solicitud en curso ...");
        }
        transicionar(solicitud, EN_CURSO, "Cliente aprobó presupuesto por $" + solicitud.getPresupuesto());
        return SolicitudResponse.desde(solicitud);
    }
```
- **RN-6:** solo se aprueba si está ASIGNADA (EN-08) **y** si el técnico ya cargó el presupuesto.
- **RN-5:** el técnico no puede tener **dos** trabajos EN_CURSO a la vez.
- Pasa a **EN_CURSO**.

### Cierre y caminos alternativos

| Método | Desde | Hacia | Detalle |
|---|---|---|---|
| `cancelar(id, motivo)` | PENDIENTE, OFERTADA, ASIGNADA | CANCELADA | Usa `validarTransicion` (no exige un único estado) y anota el motivo o "Solicitud cancelada" |
| `finalizar(id)` | EN_CURSO | FINALIZADA | El historial anota "visita $X + presupuesto $Y = $Z". `BigDecimal.add` suma exacto |
| `reprogramar(id, motivo)` | EN_CURSO | REPROGRAMADA | Falta un repuesto |
| `reanudar(id)` | REPROGRAMADA | EN_CURSO | Llegó el repuesto |
| `derivar(id, motivo)` | EN_CURSO | DERIVADA → PENDIENTE | Dos transiciones seguidas. Descarta al técnico, **borra** el costo, el diagnóstico y el presupuesto (eran de ese técnico) y, si es automática, se ofrece a otro |

### Los ayudantes privados

```java
    private Solicitud buscar(Long id) {
        return solicitudRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la solicitud con id " + id));
    }
```
- Buscar o 404.

```java
    private void exigirEstado(Solicitud solicitud, EstadoSolicitud esperado, String operacion) {
        if (solicitud.getEstado() != esperado) {
            throw new ReglaNegocioException("La solicitud " + solicitud.getId() + " está en estado "
                    + solicitud.getEstado() + "; para " + operacion + " debe estar en " + esperado + " (RN-7)");
        }
    }
```
- "Tiene que estar exactamente en este estado". Si no, un 422 con un mensaje que explica **qué** se quiso hacer y **por qué** no se pudo.

```java
    private void exigirTecnicoOfertado(Solicitud solicitud, Long tecnicoId) {
        if (!tecnicoId.equals(solicitud.getTecnicoId())) {
            throw new ReglaNegocioException("La oferta de la solicitud " + ... + " fue enviada al técnico " + ...);
        }
    }
```
- RN-4. Se usa `.equals` y no `==`, porque `Long` es un objeto y `==` compararía si es **el mismo objeto**, no el mismo número.

```java
    private boolean alcanzoLimiteEnEspera(Long tecnicoId) {
        return solicitudRepository.countByTecnicoIdAndEstadoIn(tecnicoId, EN_ESPERA) >= maxEnEspera;
    }

    private void validarLimiteEnEspera(Long tecnicoId) {
        long enEspera = solicitudRepository.countByTecnicoIdAndEstadoIn(tecnicoId, EN_ESPERA);
        if (enEspera >= maxEnEspera) {
            long enCurso = solicitudRepository.countByTecnicoIdAndEstado(tecnicoId, EN_CURSO);
            throw new ReglaNegocioException("El técnico " + tecnicoId + " alcanzó el límite de solicitudes: tiene "
                    + enEspera + " en espera (máximo " + maxEnEspera + ") y " + enCurso + " en curso (RN-5)");
        }
    }
```
- RN-5 en dos versiones: una que **pregunta** (devuelve `true` o `false`, para filtrar candidatos) y otra que **exige** (lanza 422, para `ofertar`).

```java
    private static void descartarTecnico(Solicitud solicitud) {
        if (solicitud.getTecnicoId() != null) {
            solicitud.getTecnicosDescartados().add(solicitud.getTecnicoId());
        }
        liberarTecnico(solicitud);
    }

    private static void liberarTecnico(Solicitud solicitud) {
        solicitud.setTecnicoId(null);
        solicitud.setTecnicoNombre(null);
        solicitud.setOfertaExpira(null);
    }
```
- `descartarTecnico`: lo anota en la lista negra y lo libera.
- `liberarTecnico`: borra el técnico y el vencimiento de la solicitud.

```java
    private void transicionar(Solicitud solicitud, EstadoSolicitud destino, String observaciones) {
        transicionar(solicitud, destino, observaciones, usuarioActual());
    }

    private void transicionar(Solicitud solicitud, EstadoSolicitud destino, String observaciones, String usuario) {
        EstadoSolicitud anterior = solicitud.getEstado();
        if (!anterior.puedePasarA(destino)) {
            throw new ReglaNegocioException("Transición inválida de " + anterior + " a " + destino + ...);
        }
        solicitud.setEstado(destino);
        registrarHistorial(solicitud, anterior, destino, observaciones, usuario);
        log.info("Solicitud {}: {} -> {}", solicitud.getId(), anterior, destino);
    }
```
- **El único lugar donde cambia el estado.** Siempre:
  1. revisa que el camino sea válido (con el mapa de `EstadoSolicitud`);
  2. cambia el estado;
  3. lo anota en el historial;
  4. lo loguea.
- Hay dos versiones (**sobrecarga**): una usa el usuario del token y la otra recibe el usuario (para poner `sistema`).

```java
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
```
- Guarda la fila del historial. Si la observación tiene más de 500 caracteres (el tamaño de la columna), la corta.

```java
    private static String usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? USUARIO_SISTEMA : auth.getName();
    }
```
- El nombre del usuario del token (`preferred_username`, por ejemplo `cliente1`). Si no hay usuario (el reloj), devuelve `sistema`.

---

## 10. `services/OfertaScheduler.java`: el reloj

```java
@Component
public class OfertaScheduler {

    private static final Logger log = LoggerFactory.getLogger(OfertaScheduler.class);

    private final SolicitudService solicitudService;
    ...
    @Scheduled(fixedDelayString = "${app.oferta.scheduler-ms:30000}", initialDelayString = "${app.oferta.scheduler-ms:30000}")
    public void expirarOfertas() {
```
- `@Scheduled`: Spring ejecuta este método **solo**, cada cierto tiempo.
  - `fixedDelayString`: espera 30 000 ms (30 s) **después de terminar** cada pasada. Así las pasadas nunca se superponen.
  - `initialDelayString`: la primera pasada es a los 30 s de arrancar.
  - Se configura con `OFERTA_SCHEDULER_MS`.

```java
        MDC.put(CorrelationIdFilter.MDC_KEY, "scheduler-" + UUID.randomUUID().toString().substring(0, 8));
```
- Como no hay un pedido HTTP, no hay correlation id. Se inventa uno (`scheduler-cead6ff6`) para poder seguir la pasada en los logs.

```java
        try {
            for (Long id : solicitudService.ofertasVencidas()) {
                try {
                    solicitudService.expirarOferta(id);
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.warn("Scheduler: la solicitud {} cambió mientras vencía su oferta, se respeta el otro cambio", id);
                } catch (RuntimeException e) {
                    log.error("Scheduler: error al vencer la oferta de la solicitud {}", id, e);
                }
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
```
- Por cada oferta vencida, llama a `expirarOferta`, que corre en **su propia transacción**.
- **¿Por qué está en otra clase?** `@Transactional` funciona a través de un "proxy" que Spring pone **alrededor** del bean. Si `SolicitudService` se llamara a sí mismo, la llamada no pasaría por el proxy y no habría transacción. Desde otra clase, sí pasa.
- `ObjectOptimisticLockingFailureException`: el `@Version` detectó que el técnico **aceptó justo en ese instante**. Se respeta la aceptación, sin romper nada.
- Cualquier otro error se loguea con la excepción **completa** como último argumento y se sigue con la siguiente solicitud. Una solicitud con problemas no frena a las demás.
- `finally` → limpia la mochila del MDC.

---

## 11. `controllers/SolicitudController.java`

```java
@RestController
@RequestMapping("/api/v1/solicitudes")
@Tag(name = "Solicitudes", description = "Ciclo de vida de una solicitud de servicio técnico (F4 a F9)")
public class SolicitudController {

    private static final String ROLES_TODOS = "hasAnyRole('ADMINISTRADOR','CLIENTE','TECNICO')";
    private static final String ROLES_CLIENTE = "hasAnyRole('ADMINISTRADOR','CLIENTE')";
    private static final String ROLES_TECNICO = "hasAnyRole('ADMINISTRADOR','TECNICO')";
```
- Las reglas de roles como **constantes**, para no repetir el texto en 15 métodos. Se usan así: `@PreAuthorize(ROLES_CLIENTE)`.

Cada método tiene la misma forma: anotación de ruta, `@PreAuthorize`, documentación de Swagger y **una sola línea** que llama al service. El controller no tiene lógica (lineamiento §2):

```java
    @PostMapping("/{solicitudId}/aceptar/{tecnicoId}")
    @PreAuthorize(ROLES_TECNICO)
    @Operation(summary = "El técnico acepta la oferta", description = "...")
    @ApiResponses({ ... })
    public ResponseEntity<SolicitudResponse> aceptar(@PathVariable Long solicitudId, @PathVariable Long tecnicoId) {
        return ResponseEntity.ok(solicitudService.aceptar(solicitudId, tecnicoId));
    }
```
- Dos variables en la misma ruta: `/solicitudes/7/aceptar/1` → `solicitudId = 7`, `tecnicoId = 1`.

```java
    @PostMapping("/{solicitudId}/cancelar")
    @PreAuthorize(ROLES_CLIENTE)
    public ResponseEntity<SolicitudResponse> cancelar(
            @PathVariable Long solicitudId,
            @Parameter(description = "Motivo de la cancelación", example = "Cliente rechazó el presupuesto")
            @RequestParam(required = false) String motivo) { ... }
```
- El motivo viaja como parámetro opcional de la URL: `/cancelar?motivo=No me convence`.

| Método | Ruta | Roles | Respuesta |
|---|---|---|---|
| `crear` | `POST /` | CLIENTE, ADMIN | 201 + Location |
| `listar` | `GET /?estado=` | todos | 200, página |
| `obtener` | `GET /{solicitudId}` | todos | 200 |
| `historial` | `GET /{solicitudId}/historial` | todos | 200, lista |
| `elegir` | `POST /elegir` | CLIENTE, ADMIN | 200 |
| `ofertar` | `POST /{solicitudId}/ofertar/{tecnicoId}` | CLIENTE, ADMIN | 200 |
| `aceptar` / `rechazar` | `POST /{solicitudId}/aceptar|rechazar/{tecnicoId}` | TECNICO, ADMIN | 200 |
| `cargarPresupuesto` | `POST /{solicitudId}/presupuesto` | TECNICO, ADMIN | 200 |
| `aprobarPresupuesto` | `POST /{solicitudId}/aprobar-presupuesto` | CLIENTE, ADMIN | 200 |
| `cancelar` | `POST /{solicitudId}/cancelar?motivo=` | CLIENTE, ADMIN | 200 |
| `reprogramar` / `reanudar` / `derivar` / `finalizar` | `POST /{solicitudId}/...` | TECNICO, ADMIN | 200 |

> Las acciones de negocio usan **POST** sobre un sub-recurso (`/aceptar`, `/finalizar`) porque así lo comprometió el PDF del TPI. Rutas en *spinal-case* (`aprobar-presupuesto`) y parámetros en *camelCase* (`{solicitudId}`), como pide el lineamiento §3.

---

## 12. `application.yml`: lo que tiene de más

```yaml
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 50
```
- Al listar 20 solicitudes y acceder a sus `tiposServicio` (LAZY), Hibernate los carga **en tandas de hasta 50** con una sola consulta, en vez de una por solicitud (evita el N+1).

```yaml
  security:
    oauth2:
      resourceserver: ... (igual que ms-clientes)
      client:
        registration:
          ms-solicitudes:
            provider: keycloak
            client-id: ms-solicitudes
            client-secret: ${MS_SOLICITUDES_CLIENT_SECRET:patito-secret-dev}
            authorization-grant-type: client_credentials
        provider:
          keycloak:
            token-uri: ${KEYCLOAK_TOKEN_URI:http://localhost:47390/realms/patito/protocol/openid-connect/token}
```
- La **cuenta de servicio**: quién soy (`client-id`), mi contraseña (`client-secret`, que viene de una variable de entorno, lineamiento §6), qué tipo de pedido hago (`client_credentials`) y dónde pido el token (`token-uri`).

```yaml
app:
  clientes:
    base-url: ${CLIENTES_URL:http://localhost:47383}
  tecnicos:
    base-url: ${TECNICOS_URL:http://localhost:47382}
  http:
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
  geo:
    base-url: ${GEO_URL:https://router.project-osrm.org}
    enabled: ${GEO_ENABLED:true}
    ...
  oferta:
    ventana-minutos: ${OFERTA_VENTANA_MINUTOS:15}
    scheduler-ms: ${OFERTA_SCHEDULER_MS:30000}
  tecnico:
    max-en-espera: 2
    max-en-curso: 1
```
- Las direcciones de los otros servicios, los timeouts, la API de mapas, la ventana de ofertas y los límites de RN-5.

```yaml
tarifas:
  monto-fijo: 5000.00
  por-km: 800.00
  recargo-prioridad: 0.30
  recargo-fuera-horario: 0.50
  hora-inicio: 8
  hora-fin: 18
```
- Las tarifas que lee `Tarifas`.

```yaml
resilience4j:
  retry:
    instances:
      clientes: &reintentos
        max-attempts: 2
        wait-duration: 300ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
      tecnicos: *reintentos
```
- `max-attempts: 2`: el intento original + 1 reintento.
- `wait-duration: 300ms`: cuánto espera entre los dos.
- `retry-exceptions`: **solo** reintenta errores de conexión. Un 404 o un 422 no se reintentan: volver a preguntar daría lo mismo.
- `&reintentos` y `*reintentos`: un **ancla** de YAML. Se define una vez (`&`) y se reutiliza (`*`). Técnicos usa la misma configuración que clientes.

```yaml
  circuitbreaker:
    instances:
      clientes: &circuito
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 20s
        permitted-number-of-calls-in-half-open-state: 2
        register-health-indicator: true
        allow-health-indicator-to-fail: false
        ignore-exceptions:
          - ...RecursoNoEncontradoException
          - ...ReglaNegocioException
          - ...ServicioRemotoException
      tecnicos: *circuito
```
- `sliding-window-size: 10`: mira las **últimas 10** llamadas.
- `minimum-number-of-calls: 5`: no decide nada hasta tener al menos 5.
- `failure-rate-threshold: 50`: si el 50 % o más fallaron → el circuito se **abre** (salta la térmica).
- `wait-duration-in-open-state: 20s`: se queda abierto 20 segundos (fallando al instante, sin llamar).
- `permitted-number-of-calls-in-half-open-state: 2`: pasados los 20 s, deja pasar **2 llamadas de prueba** ("medio abierto"). Si funcionan, se cierra; si no, se abre de nuevo.
- `register-health-indicator: true` + `allow-health-indicator-to-fail: false`: el estado del circuito aparece en `/actuator/health`, pero no marca a ms-solicitudes como "enfermo".
- `ignore-exceptions`: los errores de negocio **no cuentan** como fallas. Que un cliente no exista no significa que ms-clientes esté caído.
