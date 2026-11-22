# 03 – ms-clientes: la sala de los clientes (explicado entero)

## ¿Para qué sirve? (explicado fácil)

Es el **cuaderno de clientes** de la empresa: quién es cada cliente (nombre, DNI) y **dónde vive** (calle, barrio, latitud y longitud). Las coordenadas son lo más importante, porque con ellas se buscan técnicos cercanos.

> 📌 Este es el microservicio más simple, por eso se explica **entero y con todo detalle**. Las clases de `config/` y `exceptions/` (seguridad, correlation id, Swagger, errores) son **iguales** en ms-tecnicos y ms-solicitudes: solo cambia el nombre del paquete. Los otros documentos remiten a este.

```
ms-clientes/src/main/java/utnfc/isi/back/tpi/clientes/
├── MsClientesApplication.java          ← 1. arranque
├── config/
│   ├── SecurityConfig.java             ← 2. seguridad (token y roles)
│   ├── CorrelationIdFilter.java        ← 3. número de ticket en los logs
│   ├── OpenApiConfig.java              ← 4. Swagger
│   └── DataLoader.java                 ← 12. datos de prueba
├── exceptions/
│   ├── RecursoNoEncontradoException.java ← 5. "no existe" (404)
│   ├── ReglaNegocioException.java        ← 5. "no se puede" (422)
│   └── GlobalExceptionHandler.java       ← 6. traduce errores a ProblemDetail
├── models/Cliente.java                 ← 7. la tabla
├── dtos/ClienteRequest.java            ← 8. lo que entra
├── dtos/ClienteResponse.java           ← 8. lo que sale
├── repositories/ClienteRepository.java ← 9. la heladera
├── services/ClienteService.java        ← 10. el cocinero
└── controllers/ClienteController.java  ← 11. el mozo
src/main/resources/application.yml      ← 13. configuración
```

---

## 1. `MsClientesApplication.java`: el botón de encendido

```java
package utnfc.isi.back.tpi.clientes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class MsClientesApplication {
    public static void main(String[] args) {
        SpringApplication.run(MsClientesApplication.class, args);
    }
}
```
- `package ...clientes;`: el paquete raíz. Spring va a buscar componentes **en este paquete y en todos los de adentro** (`config`, `services`, etc.).
- `import ...`: "voy a usar estas clases de estas librerías". El `import static` trae una constante (`VIA_DTO`) para escribirla sin el nombre largo.
- `@SpringBootApplication`: la anotación más importante. Junta tres:
  1. `@Configuration`: esta clase puede tener configuración;
  2. `@EnableAutoConfiguration`: "Spring Boot, configurá solo todo lo que puedas" (Tomcat, Jackson, JPA, seguridad…, según las dependencias del pom);
  3. `@ComponentScan`: "buscá todas las clases con `@Component`, `@Service`, `@RestController`, etc., y creá los beans".
- `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`: cuando un endpoint devuelve una página (`Page`), Spring la convierte en un JSON prolijo y estable:
  ```json
  { "content": [ ... ], "page": { "size": 20, "number": 0, "totalElements": 3, "totalPages": 1 } }
  ```
- `public static void main(String[] args)`: el punto de entrada de **cualquier** programa Java.
- `SpringApplication.run(...)`: enciende Spring: crea todos los beans, conecta la base, levanta Tomcat en el puerto y queda esperando pedidos.

---

## 2. `config/SecurityConfig.java`: el control de pulseras

**Qué hace:** convierte al microservicio en un **Resource Server**: cada pedido tiene que traer un token JWT válido de Keycloak. Además lee los roles del token y prepara las respuestas 401 y 403 en formato ProblemDetail.

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
```
- `@Configuration`: esta clase fabrica beans de configuración.
- `@EnableWebSecurity`: activa Spring Security para las peticiones web.
- `@EnableMethodSecurity`: activa `@PreAuthorize` en los métodos (la revisión de roles en los controllers). **Sin esto, `@PreAuthorize` sería ignorado.**

```java
    private static final String[] RUTAS_PUBLICAS = {
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
            "/actuator/health/**", "/actuator/info"
    };
```
- Una lista de rutas que **no** piden token. `**` = "cualquier cosa después". Swagger y el health check tienen que verse sin login (el healthcheck de Docker no tiene token).

```java
    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
```
- `ObjectMapper` es el convertidor de Jackson (objeto ↔ JSON). Lo pedimos por constructor para escribir los errores 401 y 403 como JSON.

```java
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
```
- `@Bean SecurityFilterChain`: la **cadena de filtros de seguridad**: la lista de controles que pasa cada pedido antes de llegar al controller.
- `HttpSecurity http`: un "armador" que Spring nos presta para configurar la cadena.
- `.csrf(csrf -> csrf.disable())`: **CSRF** es una protección para sitios web con cookies de sesión. Nuestra API no usa cookies (usa un token en un header), así que no aplica y se desactiva.

```java
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
```
- La consola H2 se muestra dentro de *frames* (cuadros dentro de la página). Por defecto Spring los bloquea; `sameOrigin` los permite si vienen del mismo sitio.

```java
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```
- **STATELESS** (sin estado): el servidor **no recuerda** a nadie entre pedidos. Cada pedido trae su token y se valida de cero. Es lo que piden los lineamientos (§3).

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PathRequest.toH2Console()).permitAll()
                        .requestMatchers(RUTAS_PUBLICAS).permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMINISTRADOR")
                        .anyRequest().authenticated())
```
- Las reglas **por ruta**, que se leen de arriba hacia abajo (gana la primera que coincide):
  - `PathRequest.toH2Console()` → la consola H2: libre;
  - `RUTAS_PUBLICAS` → libre;
  - `/actuator/**` (métricas, loggers) → solo `ADMINISTRADOR`. `hasRole("ADMINISTRADOR")` busca la authority `ROLE_ADMINISTRADOR`;
  - `anyRequest().authenticated()` → todo lo demás necesita **cualquier** token válido. El rol fino se controla después, en cada método, con `@PreAuthorize`.

```java
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
```
- `.oauth2ResourceServer(...).jwt(...)`: "los pedidos traen un JWT en `Authorization: Bearer ...`: validalo". La validación usa lo que dice el `application.yml`: `issuer-uri` (quién lo emitió) y `jwk-set-uri` (dónde están las claves públicas para comprobar la firma).
- `jwtAuthenticationConverter(...)`: le decimos **cómo** sacar los roles del token (ver más abajo).

```java
                        .authenticationEntryPoint((req, res, ex) -> escribirProblema(req, res,
                                HttpStatus.UNAUTHORIZED, "No autenticado",
                                "Se requiere un token JWT válido en el header Authorization: Bearer <token>"))
                        .accessDeniedHandler((req, res, ex) -> escribirProblema(req, res,
                                HttpStatus.FORBIDDEN, "Acceso denegado",
                                "Tu rol no tiene permiso para realizar esta operación")));
        return http.build();
    }
```
- `authenticationEntryPoint`: qué responder cuando **no hay token o es inválido** → **401** con un mensaje claro. Por defecto Spring respondería 401 con el cuerpo vacío; los lineamientos piden que el error explique qué pasó.
- `accessDeniedHandler`: qué responder cuando hay token pero **no alcanza el rol** → **403**.
- `(req, res, ex) -> ...`: una lambda; recibe el pedido, la respuesta y la excepción.
- `http.build()`: arma la cadena con todas esas reglas.

```java
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extraerRoles);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
```
- El **convertidor**: transforma el token (`Jwt`) en un "usuario de Spring" (`AbstractAuthenticationToken`).
- `setJwtGrantedAuthoritiesConverter(SecurityConfig::extraerRoles)`: "para sacar los permisos, usá mi método `extraerRoles`". `::` es una **referencia a método** (como pasar la función por nombre).
- `setPrincipalClaimName("preferred_username")`: el "nombre" del usuario va a ser el claim `preferred_username` (por ejemplo `cliente1`). Por defecto sería un ID largo e ilegible.

```java
    private static Collection<GrantedAuthority> extraerRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .<GrantedAuthority>map(rol -> new SimpleGrantedAuthority("ROLE_" + rol.toString().toUpperCase()))
                .toList();
    }
```
- Keycloak guarda los roles así: `"realm_access": { "roles": ["CLIENTE", "default-roles-patito"] }`.
- `jwt.getClaim("realm_access")`: saca ese pedazo del token como un `Map` (clave → valor).
- `instanceof Collection<?> roles`: "si `roles` es una colección, guardala en la variable `roles`" (*pattern matching* de Java 21). Si no hay roles, devuelve una lista vacía (`List.of()`): el usuario queda autenticado, pero sin permisos.
- `.map(rol -> new SimpleGrantedAuthority("ROLE_" + ...toUpperCase()))`: cada rol se convierte en una **authority** con el prefijo `ROLE_` y en mayúsculas: `CLIENTE` → `ROLE_CLIENTE`. Spring necesita ese prefijo para que `hasRole('CLIENTE')` funcione (lineamiento §8).

```java
    private void escribirProblema(HttpServletRequest request, HttpServletResponse response,
                                  HttpStatus status, String titulo, String detalle) throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(request.getRequestURI()));
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problema);
    }
}
```
- Arma a mano un **ProblemDetail** (el formato estándar de errores):
  - `forStatusAndDetail`: el código (401 o 403) y la explicación;
  - `setTitle`: un título corto;
  - `setInstance`: la ruta que se pidió;
  - `setProperty("timestamp", ...)`: cuándo pasó;
  - `setProperty("correlationId", MDC.get(...))`: el número de ticket, sacado de la "mochila" del hilo (ver el filtro siguiente).
- `response.setStatus` y `setContentType("application/problem+json")`: prepara la respuesta.
- `objectMapper.writeValue(...)`: convierte el objeto a JSON y lo escribe en la respuesta.

---

## 3. `config/CorrelationIdFilter.java`: el número de ticket

**Qué hace:** a cada pedido le asigna un número (`X-Correlation-ID`), lo pone en cada línea de log y lo devuelve en la respuesta. Así, si algo falla, buscás ese número en los logs de **todos** los servicios.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
```
- `@Component`: Spring lo crea como bean y, como es un filtro, lo pone en la cadena de filtros del servidor.
- `@Order(Ordered.HIGHEST_PRECEDENCE)`: que sea **el primero** de todos, antes que la seguridad. Así hasta un 401 lleva su correlation id.
- `extends OncePerRequestFilter`: una clase base de Spring que garantiza que el filtro corra **una sola vez** por pedido.
- `HEADER` y `MDC_KEY`: constantes públicas, para que otras clases usen exactamente los mismos nombres.

```java
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
```
- `doFilterInternal`: el método que se ejecuta con cada pedido.
- Lee el header `X-Correlation-ID`. Si viene del gateway, lo usa; si no viene (por ejemplo, si llamás al microservicio directo), inventa uno con `UUID.randomUUID()`: un identificador aleatorio que prácticamente nunca se repite.

```java
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        long inicio = System.currentTimeMillis();
```
- `MDC.put(...)`: el **MDC** (*Mapped Diagnostic Context*) es una "mochila" pegada al hilo que atiende este pedido. Todo lo que se loguee mientras dure el pedido puede leer lo que hay adentro. El patrón de log tiene `%X{correlationId}`, y Logback lo reemplaza por el valor.
- `response.setHeader(...)`: lo devuelve al cliente en la respuesta.
- `inicio`: la hora actual en milisegundos, para medir cuánto tardó.

```java
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith("/api/")) {
                log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), System.currentTimeMillis() - inicio);
            }
            MDC.remove(MDC_KEY);
        }
    }
}
```
- `chain.doFilter(request, response)`: "pasale el pedido al siguiente filtro". Desde acá sigue todo el recorrido (seguridad, controller, service…) y vuelve.
- `finally`: se ejecuta **siempre**, salga bien o con error.
- Si fue un pedido a la API, anota una línea como `POST /api/v1/clientes -> 201 (15 ms)`.
- `MDC.remove(...)`: **vacía la mochila**. Los hilos de Tomcat se reutilizan; si no se limpia, el siguiente pedido heredaría un correlation id ajeno.

---

## 4. `config/OpenApiConfig.java`: la carátula de Swagger

```java
@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA = "bearer-jwt";

    @Bean
    public OpenAPI openApi(@Value("${app.openapi.titulo}") String titulo,
                           @Value("${app.openapi.descripcion}") String descripcion) {
        return new OpenAPI()
                .info(new Info().title(titulo).description(descripcion).version("v1"))
                .servers(List.of(new Server().url("/").description("Servidor actual")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA))
                .components(new Components().addSecuritySchemes(ESQUEMA, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Token de Keycloak (realm patito). Pegalo sin la palabra Bearer.")));
    }
}
```
- Springdoc arma **solo** la documentación leyendo los controllers. Este bean le agrega la "carátula":
  - `.info(...)`: título, descripción y versión. Se leen del `application.yml` (`app.openapi.*`), así cada microservicio muestra los suyos;
  - `.servers(List.of(new Server().url("/")))`: la dirección a la que Swagger manda los "Try it out". `/` significa "el mismo servidor desde donde se abrió Swagger". Funciona tanto entrando directo (`localhost:47383`) como a través del gateway (`localhost:47380`);
  - `.components(...addSecuritySchemes(...))`: declara que la API usa un token **Bearer JWT**. Esto hace aparecer el botón **Authorize** 🔓;
  - `.addSecurityItem(...)`: "todos los endpoints usan ese esquema". Swagger agrega solo el header `Authorization: Bearer <token>`.

---

## 5. Las excepciones propias

```java
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
```
- `extends RuntimeException`: una excepción "no chequeada": no hace falta declararla con `throws` en cada método. Es lo que piden los lineamientos (§5).
- `super(mensaje)`: le pasa el mensaje a la clase padre; después se lee con `ex.getMessage()`.
- **Significado:** "lo que pediste no existe" → **404**.

`ReglaNegocioException` es idéntica, pero significa "existe, pero no se puede hacer por una regla del negocio" → **422** (por ejemplo, un DNI repetido).

> 💡 Usar excepciones con **nombre de negocio** hace que el service se lea solo: `throw new ReglaNegocioException("Ya existe un cliente con DNI ...")`.

---

## 6. `exceptions/GlobalExceptionHandler.java`: el encargado de las quejas

**Qué hace:** cualquier excepción que salga de un controller llega acá y se convierte en una respuesta ProblemDetail con el código correcto. Así ningún error sale "crudo" (con el stack trace de Java).

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
```
- `@RestControllerAdvice`: "esta clase atiende las excepciones de **todos** los controllers y responde JSON".
- `extends ResponseEntityExceptionHandler`: heredamos de una clase de Spring que **ya sabe** responder los errores típicos de Spring MVC con ProblemDetail: JSON mal escrito, parámetro faltante, método HTTP no soportado (405), ruta inexistente, etc. Nosotros solo agregamos los nuestros y personalizamos algunos.

```java
    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Object> manejarNoEncontrado(RecursoNoEncontradoException ex) {
        log.warn("Recurso no encontrado: {}", ex.getMessage());
        return problema(HttpStatus.NOT_FOUND, "Recurso no encontrado", ex.getMessage());
    }
```
- `@ExceptionHandler(X.class)`: "cuando se lance una `X`, ejecutá este método".
- `log.warn(...)`: lo anota como **WARN** (anomalía, no falla grave).
- Devuelve un 404 con el mensaje de la excepción.

```java
    @ExceptionHandler(ReglaNegocioException.class)
    public ResponseEntity<Object> manejarReglaNegocio(ReglaNegocioException ex) {
        log.warn("Regla de negocio incumplida: {}", ex.getMessage());
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Regla de negocio incumplida", ex.getMessage());
    }
```
- `UNPROCESSABLE_ENTITY` = **422**: "entendí el pedido, pero no lo puedo procesar por una regla".

```java
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> manejarIntegridad(DataIntegrityViolationException ex) {
        log.warn("Violación de integridad de datos: {}", ex.getMostSpecificCause().getMessage());
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Dato duplicado o inválido",
                "La operación viola una restricción de datos (por ejemplo, un valor único repetido)");
    }
```
- Red de seguridad: si dos pedidos con el mismo DNI llegan **exactamente** al mismo tiempo, los dos pasan el `existsByDni`, pero la base rechaza el segundo por la restricción `UNIQUE`. Acá se traduce a 422 en vez de 500.
- Al cliente se le da un mensaje genérico: no mostramos el error SQL interno.

```java
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> manejarConcurrencia(ObjectOptimisticLockingFailureException ex) { ... 422 ... }
```
- Cuando dos operaciones modifican la misma fila a la vez y gana la otra (bloqueo optimista con `@Version`; se usa en ms-solicitudes) → 422 "volvé a intentarlo".

```java
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> manejarAccesoDenegado(AccessDeniedException ex) {
        log.warn("Acceso denegado: {}", ex.getMessage());
        return problema(HttpStatus.FORBIDDEN, "Acceso denegado",
                "Tu rol no tiene permiso para realizar esta operación");
    }
```
- **¡Muy importante!** Cuando `@PreAuthorize` rechaza, lanza `AccessDeniedException` **desde adentro del controller**. Si no la atrapáramos acá, caería en el manejador genérico de abajo y respondería **500** en vez de **403**.

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> manejarInesperado(Exception ex) {
        log.error("Error inesperado", ex);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Ocurrió un error inesperado. Informá el correlationId al equipo de soporte.");
    }
```
- La **red final**: cualquier otra excepción → **500**.
- `log.error("Error inesperado", ex)`: acá sí se loguea como **ERROR** y con la excepción completa (el stack trace) **como último argumento**, que es lo que piden los lineamientos. Al cliente no se le muestran detalles internos.

```java
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errores = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errores.putIfAbsent(error.getField(), error.getDefaultMessage()));
        log.warn("Validación fallida: {}", errores);
        ProblemDetail problema = crearProblema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "Uno o más campos no cumplen las validaciones");
        problema.setProperty("errores", errores);
        return ResponseEntity.badRequest().body(problema);
    }
```
- `@Override`: reemplazamos cómo la clase padre maneja los errores de `@Valid` en el **cuerpo** del pedido.
- `getFieldErrors()`: la lista de campos que fallaron.
- `putIfAbsent(campo, mensaje)`: arma un mapa `campo → mensaje`; si un campo falló dos veces, se queda con el primer mensaje.
- El resultado es un **400** con el detalle de cada campo, como pide el lineamiento §5:
  ```json
  { "status": 400, "title": "Solicitud inválida", "errores": { "dni": "El DNI es obligatorio", "calle": "La calle es obligatoria" } }
  ```

```java
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex, ...) {
        Map<String, String> errores = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(resultado -> errores.putIfAbsent(
                resultado.getMethodParameter().getParameterName(),
                resultado.getResolvableErrors().isEmpty() ? "valor inválido"
                        : resultado.getResolvableErrors().getFirst().getDefaultMessage()));
        ...
    }
```
- Lo mismo, pero para validaciones de **parámetros de la URL** (por ejemplo `?latitud=-131`, que tiene `@DecimalMin("-90.0")`). `getParameterName()` da el nombre del parámetro (`latitud`).

```java
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> respuesta = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (respuesta != null && respuesta.getBody() instanceof ProblemDetail problema) {
            completar(problema);
        }
        return respuesta;
    }
```
- Todos los errores estándar de Spring pasan por este método de la clase padre. Lo "interceptamos" para agregarles `timestamp` y `correlationId`, así **todos** los errores tienen la misma forma (lineamiento §3: "si se redefine un código, se aplica igual en todo el sistema").
- `super.handleExceptionInternal(...)`: primero deja que el padre arme la respuesta, y después la completamos.

```java
    private ResponseEntity<Object> problema(HttpStatus status, String titulo, String detalle) {
        return ResponseEntity.status(status).body(crearProblema(status, titulo, detalle));
    }

    private ProblemDetail crearProblema(HttpStatus status, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setTitle(titulo);
        completar(problema);
        return problema;
    }

    private void completar(ProblemDetail problema) {
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
```
- Tres ayudantes para no repetir código: `problema` arma la respuesta, `crearProblema` arma el ProblemDetail y `completar` le agrega la hora y el ticket.
- El campo `instance` (la ruta pedida) lo completa Spring solo al devolver la respuesta.

---

## 7. `models/Cliente.java`: la tabla de la base

```java
@Entity
@Table(name = "cliente")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Cliente {
```
- `@Entity`: "esta clase es una **tabla** de la base de datos; cada objeto es una fila". La maneja JPA/Hibernate.
- `@Table(name = "cliente")`: el nombre exacto de la tabla.
- **Anotaciones de Lombok** (escriben código por nosotros cuando se compila):
  - `@Data`: getters (`getNombre()`), setters (`setNombre(...)`), `toString()`, `equals()` y `hashCode()`;
  - `@NoArgsConstructor`: un constructor vacío `new Cliente()`. **JPA lo necesita sí o sí** para crear objetos al leer de la base;
  - `@AllArgsConstructor`: un constructor con todos los campos (lo usa `@Builder`);
  - `@Builder`: permite armar objetos así: `Cliente.builder().nombre("Carlos").dni("30111222").build()`;
  - `@EqualsAndHashCode(of = "id")`: dos clientes son "iguales" si tienen el mismo `id`. Lo piden los lineamientos (§4) para evitar problemas con las relaciones.

```java
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
```
- `@Id`: la **clave primaria** (el número único de cada fila).
- `@GeneratedValue(strategy = IDENTITY)`: lo genera la base sola (1, 2, 3…) cuando se inserta la fila.

```java
    @Column(nullable = false, length = 80)
    private String nombre;

    @Column(nullable = false, length = 80)
    private String apellido;

    @Column(nullable = false, unique = true, length = 15)
    private String dni;

    @Column(nullable = false, length = 120)
    private String calle;

    @Column(length = 80)
    private String barrio;

    @Column(nullable = false)
    private Double latitud;

    @Column(nullable = false)
    private Double longitud;
}
```
- Cada campo es una **columna**. `@Column` le da reglas a nivel base de datos (son validaciones estructurales, como piden los lineamientos):
  - `nullable = false` → `NOT NULL`: no puede quedar vacía;
  - `length = 80` → `VARCHAR(80)`: hasta 80 caracteres;
  - `unique = true` en el DNI → la base **rechaza** dos clientes con el mismo DNI.
- `barrio` no tiene `nullable = false`: es opcional.
- `Double` (con mayúscula, la versión "objeto" de `double`) permite que JPA trabaje con `null` mientras arma el objeto.

---

## 8. Los DTOs: las bandejas

### `dtos/ClienteRequest.java`: lo que **entra**

```java
@Schema(description = "Datos de entrada de un cliente")
public record ClienteRequest(
        @Schema(example = "Pedro") @NotBlank(message = "El nombre es obligatorio") @Size(max = 80)
        String nombre,
        ...
        @Schema(example = "40111222", description = "Solo números, 7 u 8 dígitos")
        @NotBlank(message = "El DNI es obligatorio")
        @Pattern(regexp = "\\d{7,8}", message = "El DNI debe tener 7 u 8 dígitos")
        String dni,
        ...
        @Schema(example = "-31.4135") @NotNull(message = "La latitud es obligatoria")
        @DecimalMin(value = "-90.0", message = "La latitud debe estar entre -90 y 90")
        @DecimalMax(value = "90.0", message = "La latitud debe estar entre -90 y 90")
        Double latitud,
        ...
) {
}
```
- `record`: solo datos (ver commons-geo). El cliente manda un JSON y Jackson lo convierte en este record.
- `@Schema(example = ..., description = ...)`: para **Swagger**. El ejemplo aparece cargado en "Try it out".
- **Validaciones de Bean Validation** (se revisan cuando el controller pone `@Valid`):
  - `@NotBlank`: no puede ser `null` ni texto vacío ni solo espacios;
  - `@Size(max = 80)`: hasta 80 caracteres;
  - `@Pattern(regexp = "\\d{7,8}")`: una **expresión regular**: `\d` = un dígito, `{7,8}` = entre 7 y 8 veces. "Solo 7 u 8 números";
  - `@NotNull`: no puede faltar (para números);
  - `@DecimalMin` / `@DecimalMax`: rango permitido. La latitud va de -90 a 90 y la longitud de -180 a 180;
  - `message = "..."`: el texto que aparece en `errores` si falla.
- **¿Por qué un DTO y no la entidad directamente?** Para que el cliente no pueda mandar un `id` inventado y para cambiar la base sin romper la API (lineamiento §2: "no exponer entidades JPA").

### `dtos/ClienteResponse.java`: lo que **sale**

```java
public record ClienteResponse(Long id, String nombre, String apellido, String dni, String calle, String barrio,
                              Double latitud, Double longitud) {
    public static ClienteResponse desde(Cliente c) {
        return new ClienteResponse(c.getId(), c.getNombre(), c.getApellido(), c.getDni(),
                c.getCalle(), c.getBarrio(), c.getLatitud(), c.getLongitud());
    }
}
```
- Los datos que se devuelven (incluye el `id`).
- `static ClienteResponse desde(Cliente c)`: un **método de fábrica** que copia los datos de la entidad al DTO. Se usa así: `ClienteResponse.desde(cliente)` o, en un stream, `.map(ClienteResponse::desde)`.

---

## 9. `repositories/ClienteRepository.java`: la heladera

```java
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    boolean existsByDni(String dni);

    boolean existsByDniAndIdNot(String dni, Long id);

    Page<Cliente> findByApellidoContainingIgnoreCase(String apellido, Pageable pageable);
}
```
- Es una **interfaz sin código**. Spring Data escribe la implementación sola al arrancar.
- `extends JpaRepository<Cliente, Long>`: "repositorio de `Cliente`, cuyo id es `Long`". Trae listos `save`, `findById`, `findAll`, `findAll(Pageable)`, `delete`, `count`… Los lineamientos piden `JpaRepository` y no `CrudRepository`, porque trae paginación.
- **Consultas derivadas por nombre** (lineamiento §4): Spring **lee el nombre del método** y arma el SQL:
  - `existsByDni(dni)` → `SELECT COUNT(*) > 0 FROM cliente WHERE dni = ?`;
  - `existsByDniAndIdNot(dni, id)` → `... WHERE dni = ? AND id <> ?`: "¿hay **otro** cliente con este DNI?" (para modificar sin chocar consigo mismo);
  - `findByApellidoContainingIgnoreCase(apellido, pageable)` → `... WHERE UPPER(apellido) LIKE UPPER('%garc%')`, paginado. `Containing` = "contiene", `IgnoreCase` = sin importar mayúsculas.

---

## 10. `services/ClienteService.java`: el cocinero

```java
@Service
public class ClienteService {

    private static final Logger log = LoggerFactory.getLogger(ClienteService.class);

    private final ClienteRepository clienteRepository;

    public ClienteService(ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }
```
- `@Service`: un bean de la capa de **lógica de negocio**.
- **Inyección por constructor**: el service pide el repositorio y Spring se lo pasa. `final` garantiza que nunca cambie.

### Crear

```java
    @Transactional
    public ClienteResponse crear(ClienteRequest request) {
        if (clienteRepository.existsByDni(request.dni())) {
            throw new ReglaNegocioException("Ya existe un cliente con DNI " + request.dni());
        }
        Cliente cliente = new Cliente();
        copiarDatos(request, cliente);
        Cliente guardado = clienteRepository.save(cliente);
        log.info("Cliente creado id={}", guardado.getId());
        return ClienteResponse.desde(guardado);
    }
```
- `@Transactional`: todo el método es una transacción (todo o nada).
- **Regla de negocio:** si el DNI ya existe → `ReglaNegocioException` → **422**.
- `new Cliente()` + `copiarDatos(...)`: pasa los datos del DTO a la entidad.
- `clienteRepository.save(cliente)`: hace el `INSERT` y devuelve el cliente **con el id** que generó la base.
- `log.info(...)`: un evento de negocio se anota como **INFO**. Solo se loguea el id, no datos personales (lineamiento §9).
- Devuelve el DTO de salida.

### Listar (paginado)

```java
    @Transactional(readOnly = true)
    public Page<ClienteResponse> listar(String apellido, Pageable pageable) {
        Page<Cliente> pagina = (apellido == null || apellido.isBlank())
                ? clienteRepository.findAll(pageable)
                : clienteRepository.findByApellidoContainingIgnoreCase(apellido, pageable);
        return pagina.map(ClienteResponse::desde);
    }
```
- `readOnly = true`: una transacción **solo de lectura** (Hibernate se ahorra trabajo).
- `condición ? A : B`: el **operador ternario**, un `if` en una línea. Sin filtro trae todos; con filtro, busca por apellido.
- `Pageable`: trae la página pedida (número, tamaño y orden). Nunca se traen todos los registros de golpe (lineamiento §2: evitar `findAll()` sin paginar).
- `pagina.map(ClienteResponse::desde)`: convierte cada entidad de la página en un DTO, conservando los datos de paginación.

### Obtener, actualizar y eliminar

```java
    @Transactional(readOnly = true)
    public ClienteResponse obtener(Long id) {
        return ClienteResponse.desde(buscar(id));
    }

    @Transactional
    public ClienteResponse actualizar(Long id, ClienteRequest request) {
        Cliente cliente = buscar(id);
        if (clienteRepository.existsByDniAndIdNot(request.dni(), id)) {
            throw new ReglaNegocioException("Ya existe otro cliente con DNI " + request.dni());
        }
        copiarDatos(request, cliente);
        log.info("Cliente actualizado id={}", id);
        return ClienteResponse.desde(cliente);
    }
```
- `actualizar`: busca el cliente (404 si no existe), revisa que el DNI nuevo no sea de **otro** cliente y copia los datos.
- **¡Ojo! No hay `save()`**. Como el cliente se leyó dentro de una transacción, Hibernate lo está "vigilando" (está *managed*). Al terminar el método, detecta qué cambió y hace el `UPDATE` solo. Esto se llama **dirty checking**.

```java
    @Transactional
    public void eliminar(Long id) {
        Cliente cliente = buscar(id);
        clienteRepository.delete(cliente);
        log.info("Cliente eliminado id={}", id);
    }

    private Cliente buscar(Long id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el cliente con id " + id));
    }
```
- `buscar`: un ayudante privado que se usa en todos lados. `findById` devuelve un `Optional`: si está vacío, `orElseThrow` lanza la excepción → **404**.

```java
    private void copiarDatos(ClienteRequest request, Cliente cliente) {
        cliente.setNombre(request.nombre());
        ...
        cliente.setLongitud(request.longitud());
    }
}
```
- Copia campo por campo del DTO a la entidad. Se usa al crear y al actualizar, así no se repite.

---

## 11. `controllers/ClienteController.java`: el mozo

```java
@RestController
@RequestMapping("/api/v1/clientes")
@Tag(name = "Clientes", description = "Padrón de clientes con domicilio y coordenadas (F2)")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }
```
- `@RestController`: un controller que responde **datos** (JSON), no páginas HTML.
- `@RequestMapping("/api/v1/clientes")`: todos los endpoints de esta clase empiezan con esa ruta. Recurso en plural y en minúsculas, con versión `v1` (lineamiento §3).
- `@Tag(...)`: agrupa estos endpoints en Swagger bajo el título "Clientes".
- El controller solo conoce al **service**, nunca al repositorio.

### POST: crear

```java
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE')")
    @Operation(summary = "Registrar un cliente", description = "Roles: ADMINISTRADOR, CLIENTE. El DNI no se puede repetir.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cliente creado. El header Location tiene su URI"),
            @ApiResponse(responseCode = "400", description = "Faltan datos o tienen formato inválido"),
            ...
    })
    public ResponseEntity<ClienteResponse> crear(@Valid @RequestBody ClienteRequest request) {
        ClienteResponse creado = clienteService.crear(request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{clienteId}").buildAndExpand(creado.id()).toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }
```
- `@PostMapping`: atiende `POST /api/v1/clientes`.
- `@PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE')")`: solo esos roles; el resto recibe **403**.
- `@Operation` y `@ApiResponses`: la documentación de Swagger: un resumen y qué códigos puede devolver (lineamiento §3).
- `@RequestBody`: "el parámetro viene en el cuerpo del pedido, como JSON".
- `@Valid`: "antes de entrar, revisá las validaciones del DTO". Si falla → **400** (lo arma el `GlobalExceptionHandler`).
- `ServletUriComponentsBuilder.fromCurrentRequest().path("/{clienteId}").buildAndExpand(id)`: arma la URL del recurso nuevo, por ejemplo `http://localhost:47383/api/v1/clientes/4`.
- `ResponseEntity.created(ubicacion).body(creado)`: responde **201 Created** con el header `Location` y el cliente en el cuerpo (lineamiento §3). Siempre `ResponseEntity` (lineamiento §2).

### GET: listar

```java
    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<Page<ClienteResponse>> listar(
            @Parameter(description = "Parte del apellido a buscar", example = "garc")
            @RequestParam(required = false) String apellido,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(clienteService.listar(apellido, pageable));
    }
```
- Solo el **ADMINISTRADOR** puede ver la lista completa de clientes (son datos personales).
- `@RequestParam(required = false) String apellido`: un parámetro **opcional** de la URL: `?apellido=garc`.
- `Pageable pageable`: Spring lo arma solo con `?page=0&size=10&sort=apellido,desc`.
- `@PageableDefault(size = 20, sort = "id", ...)`: los valores si no se mandan.
- `@ParameterObject`: para que Swagger muestre `page`, `size` y `sort` como parámetros separados.
- `ResponseEntity.ok(...)`: **200 OK**.

### GET por id, PUT y DELETE

```java
    @GetMapping("/{clienteId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE','SISTEMA')")
    public ResponseEntity<ClienteResponse> obtener(@PathVariable Long clienteId) {
        return ResponseEntity.ok(clienteService.obtener(clienteId));
    }
```
- `"/{clienteId}"`: una parte variable de la ruta. `@PathVariable Long clienteId` la lee y la convierte a número. Si mandás `/abc` → 400.
- El rol `SISTEMA` es la cuenta de servicio de ms-solicitudes (ver el documento 05).

```java
    @PutMapping("/{clienteId}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE')")
    public ResponseEntity<ClienteResponse> actualizar(@PathVariable Long clienteId,
                                                      @Valid @RequestBody ClienteRequest request) { ... ok ... }

    @DeleteMapping("/{clienteId}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<Void> eliminar(@PathVariable Long clienteId) {
        clienteService.eliminar(clienteId);
        return ResponseEntity.noContent().build();
    }
```
- `@PutMapping`: **reemplazo total**: hay que mandar todos los datos.
- `@DeleteMapping` → `ResponseEntity.noContent()` = **204 No Content**: "listo, no tengo nada para devolverte". `Void` = sin cuerpo.

---

## 12. `config/DataLoader.java`: los datos de prueba

```java
@Component
public class DataLoader implements CommandLineRunner {
    ...
    @Override
    @Transactional
    public void run(String... args) {
        if (clienteRepository.count() > 0) {
            log.info("La base de clientes ya tiene datos, no se cargan datos de prueba");
            return;
        }
        clienteRepository.saveAll(List.of(
                Cliente.builder().nombre("Carlos").apellido("García").dni("30111222")
                        .calle("Av. Colón 150").barrio("Centro").latitud(-31.420).longitud(-64.188).build(),
                ...
        ));
        log.info("Datos de prueba cargados: 3 clientes");
    }
}
```
- `implements CommandLineRunner`: Spring ejecuta el método `run` **una vez**, apenas termina de arrancar.
- `if (count() > 0) return;`: como la base H2 está **en archivo**, los datos sobreviven al reinicio. Sin este control, cada vez que se reinicia se duplicarían los clientes. Se dice que la carga es **idempotente**.
- `saveAll(List.of(...))`: guarda los 3 clientes del PDF. Carlos queda en `(-31.420, -64.188)`, como pide la comprobación 1.

---

## 13. `application.yml`: la configuración

```yaml
server:
  port: ${SERVER_PORT:47383}
```
- El puerto. `${SERVER_PORT:47383}` = "si existe la variable de entorno `SERVER_PORT` usala; si no, 47383".

```yaml
spring:
  application:
    name: ms-clientes
```
- El nombre del servicio. Aparece en cada línea de log (`[ms-clientes]`).

```yaml
  datasource:
    url: jdbc:h2:file:${H2_DIR:./data}/clientes-db;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
```
- `url`: dónde está la base. `jdbc:h2:file:` = H2 **en archivo**. `${H2_DIR:./data}`: en Docker vale `/app/data` (un volumen); en tu máquina, la carpeta `./data`. `AUTO_SERVER=TRUE` permite que otro programa (la consola H2, por ejemplo) se conecte al mismo archivo.
- `username: sa` y `password` vacía: el usuario por defecto de H2.

```yaml
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```
- `ddl-auto: update`: al arrancar, Hibernate compara las entidades con las tablas y **crea o agrega** lo que falte. No borra datos.
- `open-in-view: false`: cierra la conexión a la base al terminar el service, no al terminar de escribir la respuesta. Obliga a armar los DTOs dentro del service (buena práctica) y saca un warning de Spring.

```yaml
  h2:
    console:
      enabled: true
      path: /h2-console
      settings:
        web-allow-others: true
```
- Activa la consola web de H2 en `/h2-console`. `web-allow-others` permite entrar desde fuera del contenedor.

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:47390/realms/patito}
          jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:47390/realms/patito/protocol/openid-connect/certs}
```
- `issuer-uri`: quién **tiene que haber emitido** el token. Se compara con el claim `iss`.
- `jwk-set-uri`: de dónde **bajar las claves públicas** para comprobar la firma.
- **¿Por qué los dos?** En Docker, el token dice `iss = http://localhost:47390/...` (porque Postman lo pidió desde tu máquina), pero desde adentro de Docker Keycloak se llama `keycloak:8080`. Entonces: el issuer se compara con `localhost:47390` y las claves se bajan de `keycloak:8080`. Es la "trampa" de Docker resuelta.

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    persist-authorization: true
```
- Dónde quedan el JSON de OpenAPI y la página de Swagger. `persist-authorization`: Swagger recuerda el token aunque recargues la página.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,loggers
```
- Qué endpoints de **Actuator** se exponen (lineamiento §9). `loggers` permite cambiar el nivel de log en caliente.

```yaml
app:
  openapi:
    titulo: MS-Clientes - Técnica Patito
    descripcion: ...

logging:
  pattern:
    correlation: "[cid:%X{correlationId:-}] "
  level:
    root: INFO
    utnfc.isi.back.tpi: DEBUG
```
- `app.openapi.*`: propiedades **nuestras** (las lee `OpenApiConfig`).
- `logging.pattern.correlation`: agrega `[cid:...]` a cada línea de log. `%X{correlationId:-}` = "el valor de `correlationId` del MDC, o vacío si no hay".
- `logging.level`: INFO para todo y DEBUG para nuestro código (`utnfc.isi.back.tpi`).
- **No hay `logging.file`**: los logs van a la consola (stdout), como piden los lineamientos para Docker.
