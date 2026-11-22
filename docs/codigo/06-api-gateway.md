# 06 – api-gateway: el señor de la puerta

## ¿Para qué sirve? (explicado fácil)

Es la **única puerta** del edificio (puerto 47380). Nadie de afuera habla directo con las salas. El señor de la puerta hace tres cosas:

1. **Le da un número de ticket** a cada visitante (`X-Correlation-ID`).
2. **Revisa la pulsera** (el token JWT). Si no tenés pulsera o es falsa → **401**, y no entrás.
3. **Te lleva a la sala correcta** según la dirección que pediste (`/api/v1/clientes/...` → ms-clientes).

Además tiene en la entrada un **cartel con el menú de todas las salas**: el Swagger unificado.

> 🔑 **Diferencia importante:** el gateway **no** usa Spring MVC (servlets, como los microservicios), sino **Spring WebFlux** (reactivo). Por eso algunas clases tienen otros nombres (`ServerHttpSecurity`, `WebFilter`, `Mono`). La idea es la misma.

```
api-gateway/src/main/java/utnfc/isi/back/tpi/gateway/
├── ApiGatewayApplication.java
└── config/
    ├── GatewayRoutesConfig.java   ← el mapa de rutas
    ├── SecurityConfig.java        ← revisión de pulseras (401)
    └── CorrelationIdFilter.java   ← número de ticket
src/main/resources/application.yml
```

---

## ¿Qué es "reactivo"? (en dos líneas)

- En **MVC** (los microservicios), cada pedido ocupa un **hilo** (un trabajador) de principio a fin, aunque esté esperando.
- En **WebFlux** (el gateway), pocos hilos atienden muchísimos pedidos: mientras uno espera la respuesta de un microservicio, el hilo atiende a otro. Los resultados "prometidos" se representan con `Mono<T>`: **una caja que va a tener un valor más adelante**. Es ideal para un gateway, que se pasa el día esperando a otros.

---

## `ApiGatewayApplication.java`

```java
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```
- Igual que en los microservicios: el botón de encendido. Como en el pom está `spring-cloud-starter-gateway`, Spring Boot arranca **Netty** (un servidor reactivo) en lugar de Tomcat.

---

## `config/GatewayRoutesConfig.java`: el mapa de rutas

```java
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator configurarRutas(
            RouteLocatorBuilder builder,
            @Value("${app.routes.clientes-url}") String urlClientes,
            @Value("${app.routes.tecnicos-url}") String urlTecnicos,
            @Value("${app.routes.solicitudes-url}") String urlSolicitudes) {
```
- `RouteLocator`: el bean que contiene **todas las rutas** del gateway (lineamiento §7).
- `RouteLocatorBuilder builder`: el armador de rutas que presta Spring Cloud Gateway.
- Las tres URLs destino salen de la **configuración**, no están escritas en el código. En Docker son `http://ms-clientes:47383`, etc.; en tu máquina, `http://localhost:47383`.

```java
        return builder.routes()
                .route("ms-clientes", r -> r.path("/api/v1/clientes", "/api/v1/clientes/**").uri(urlClientes))
```
- `.route("ms-clientes", ...)`: una ruta con nombre (aparece en `/actuator/gateway/routes`).
- `r.path("/api/v1/clientes", "/api/v1/clientes/**")`: el **predicado**, la condición. "Si el path es exactamente `/api/v1/clientes` o empieza con `/api/v1/clientes/`…". Hacen falta los dos: `/**` no incluye la ruta sin barra final.
- `.uri(urlClientes)`: "…mandalo a ms-clientes". El path se conserva tal cual: `/api/v1/clientes/1` → `http://ms-clientes:47383/api/v1/clientes/1`.
- Los headers (incluido el token y el correlation id) viajan con el pedido.

```java
                .route("ms-tecnicos", r -> r.path("/api/v1/tecnicos", "/api/v1/tecnicos/**",
                        "/api/v1/certificaciones", "/api/v1/zonas").uri(urlTecnicos))
                .route("ms-solicitudes", r -> r.path("/api/v1/solicitudes", "/api/v1/solicitudes/**").uri(urlSolicitudes))
```
- ms-tecnicos atiende 3 recursos: técnicos, certificaciones y zonas.

```java
                .route("docs-clientes", r -> r.path("/docs/clientes/v3/api-docs")
                        .filters(f -> f.setPath("/v3/api-docs")).uri(urlClientes))
                .route("docs-tecnicos", ...)
                .route("docs-solicitudes", ...)
                .build();
    }
}
```
- Rutas para la **documentación**: el Swagger del gateway le pide a cada microservicio su descripción OpenAPI.
- `.filters(f -> f.setPath("/v3/api-docs"))`: un **filtro** que **cambia el path** antes de reenviar. El navegador pide `/docs/clientes/v3/api-docs` y a ms-clientes le llega `/v3/api-docs`.
- **¿Por qué `/docs/...` y no `/v3/api-docs/clientes`?** Porque `/v3/api-docs/{grupo}` ya lo usa el propio Springdoc del gateway, y las dos rutas chocarían.
- `.build()`: arma el `RouteLocator` con todas las rutas.

---

## `config/SecurityConfig.java`: la revisión de pulseras

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
```
- `@EnableWebFluxSecurity`: la versión **reactiva** de `@EnableWebSecurity`.

```java
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
```
- `SecurityWebFilterChain` y `ServerHttpSecurity`: los equivalentes reactivos de `SecurityFilterChain` y `HttpSecurity`.
- CSRF desactivado (mismo motivo que en los microservicios: no hay cookies).

```java
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/webjars/**",
                                "/v3/api-docs/**", "/docs/**").permitAll()
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
```
- `authorizeExchange`: las reglas **por ruta** (*exchange* = el par pedido-respuesta en WebFlux).
  - health e info: libres (los usa el healthcheck de Docker);
  - Swagger y la documentación: libres;
  - **`/api/v1/**` → `authenticated()`**: hace falta un token **válido**, de cualquier rol. Este es el **primer punto de control** (lineamiento §7). El rol fino lo decide cada microservicio con `@PreAuthorize` (como comprometió el PDF: *"el microservicio devuelve 403"*);
  - `anyExchange().denyAll()`: **todo lo demás, prohibido**. Si alguien pide una ruta que no existe, no se le revela nada.

```java
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
                        .authenticationEntryPoint((exchange, ex) -> escribirProblema(exchange, HttpStatus.UNAUTHORIZED,
                                "No autenticado",
                                "Se requiere un token JWT válido en el header Authorization: Bearer <token>"))
                        .accessDeniedHandler(...))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(... 401 ...)
                        .accessDeniedHandler((exchange, ex) -> escribirProblema(exchange, HttpStatus.FORBIDDEN,
                                "Acceso denegado", "La ruta no existe o no está permitida")));
        return http.build();
    }
```
- Igual que en los microservicios: el gateway es un **Resource Server** que valida el JWT (firma, vencimiento e issuer). Los errores 401 y 403 salen como **ProblemDetail**.
- Se configuran en dos lugares: en `oauth2ResourceServer` (cuando el token es inválido) y en `exceptionHandling` (cuando no hay token o se pide una ruta prohibida).

```java
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extraerRoles);
        converter.setPrincipalClaimName("preferred_username");
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
```
- El mismo convertidor de roles que en los microservicios (`realm_access.roles` → `ROLE_X`), envuelto en un **adaptador reactivo** (`ReactiveJwtAuthenticationConverterAdapter`), porque WebFlux espera que devuelva un `Mono`.

```java
    private Mono<Void> escribirProblema(ServerWebExchange exchange, HttpStatus status, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problema.setProperty("timestamp", Instant.now());
        problema.setProperty("correlationId", exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(problema);
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            exchange.getResponse().setStatusCode(status);
            return exchange.getResponse().setComplete();
        }
    }
```
- Arma el ProblemDetail igual que en los microservicios.
- El `correlationId` sale del **header** del pedido (en el gateway no hay MDC, porque WebFlux cambia de hilo todo el tiempo).
- Escribir la respuesta en WebFlux:
  1. `writeValueAsBytes`: convierte el objeto a JSON en bytes;
  2. `bufferFactory().wrap(bytes)`: los mete en un "buffer" (un paquete de datos);
  3. `writeWith(Mono.just(buffer))`: "escribí este paquete cuando puedas". Devuelve un `Mono<Void>`: la promesa de que "va a terminar".
- Si la conversión falla (no debería), responde el status sin cuerpo.

---

## `config/CorrelationIdFilter.java`: el número de ticket

```java
@Component
@Order(-200)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-ID";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
```
- `implements WebFilter`: un filtro **reactivo** que se aplica a **todo** pedido.
- `@Order(-200)`: los filtros de seguridad de Spring corren con orden -100. Con -200 este va **antes**, así hasta un 401 lleva su correlation id.

```java
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = recibido == null || recibido.isBlank() ? UUID.randomUUID().toString() : recibido;
```
- Si el pedido ya trae un `X-Correlation-ID` (por ejemplo, el que manda Postman), lo respeta; si no, crea uno nuevo. **Aquí nace el ticket** (lineamiento §9: "se genera en el gateway si no viene").

```java
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();
```
- En WebFlux los pedidos son **inmutables**: no se pueden cambiar, se hace una **copia modificada** con `mutate()`. Esta copia lleva el header, y es la que viaja al microservicio.

```java
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
```
- `beforeCommit`: "justo **antes** de enviar la respuesta, ejecutá esto". Pone el header en la respuesta con `set`, que **reemplaza** el valor. ¿Por qué no al principio? Porque el microservicio también devuelve un `X-Correlation-ID`, y el gateway lo copia: si lo hubiéramos puesto antes, la respuesta tendría el header **dos veces**.

```java
        long inicio = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(senal -> {
                    if (request.getPath().value().startsWith("/api/")) {
                        log.info("[cid:{}] {} {} -> {} ({} ms)", correlationId, request.getMethod(),
                                request.getPath().value(), exchange.getResponse().getStatusCode(),
                                System.currentTimeMillis() - inicio);
                    }
                });
    }
}
```
- `chain.filter(...)`: sigue la cadena (seguridad → rutas → microservicio) con el pedido modificado.
- `.doFinally(...)`: "cuando **termine** (bien o mal), ejecutá esto". Anota el pedido con su status y su tiempo, por ejemplo `[cid:abc…] POST /api/v1/solicitudes -> 201 CREATED (85 ms)`.
- El `[cid:...]` se escribe a mano en el mensaje, porque en WebFlux el MDC no viaja entre hilos.

---

## `application.yml`

```yaml
server:
  port: ${SERVER_PORT:47380}

spring:
  application:
    name: api-gateway
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:47390/realms/patito}
          jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:47390/realms/patito/protocol/openid-connect/certs}
```
- El puerto 47380 y la validación del JWT (mismo truco issuer + jwk que en los microservicios; ver ms-clientes).

```yaml
app:
  routes:
    clientes-url: ${CLIENTES_URL:http://localhost:47383}
    tecnicos-url: ${TECNICOS_URL:http://localhost:47382}
    solicitudes-url: ${SOLICITUDES_URL:http://localhost:47381}
```
- Las direcciones de cada sala. En Docker se reemplazan por `http://ms-clientes:47383`, etc.

```yaml
springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    path: /swagger-ui.html
    persist-authorization: true
    urls:
      - name: MS-Clientes
        url: /docs/clientes/v3/api-docs
      - name: MS-Técnicos
        url: /docs/tecnicos/v3/api-docs
      - name: MS-Solicitudes
        url: /docs/solicitudes/v3/api-docs
    urls-primary-name: MS-Solicitudes
```
- El **Swagger unificado**: `urls` es la lista de definiciones que aparece en el selector "Select a definition". Cada una apunta a una de las rutas `/docs/...` del gateway.
- `urls-primary-name`: cuál se muestra primero.
- Como cada microservicio declara su servidor como `/`, los "Try it out" van a `localhost:47380/api/v1/...` y pasan por el gateway.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,loggers,gateway
```
- `gateway`: un endpoint de Actuator que lista las rutas configuradas (`/actuator/gateway/routes`). Como `/actuator/**` (salvo health e info) cae en `denyAll`, desde afuera no se puede ver.
