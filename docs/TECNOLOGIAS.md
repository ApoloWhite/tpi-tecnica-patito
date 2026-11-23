# Tecnologías y frameworks usados

## Explicado fácil

Para construir una casa se usan **materiales** (ladrillos, cemento) y **herramientas** (pala, nivel). En el software pasa lo mismo:

- un **lenguaje** es el idioma en que se escribe el programa (Java, JavaScript);
- un **framework** es una "casa prefabricada" a la que le agregás tus habitaciones. Spring Boot ya trae el servidor web, la seguridad y la conexión a la base: vos escribís solo la lógica del negocio. **El framework te llama a vos** (por ejemplo, Spring ejecuta tu controller cuando llega un pedido);
- una **librería** es una herramienta que **vos llamás** cuando la necesitás (Lombok, Jackson);
- la **infraestructura** es el terreno y los servicios de la casa (Docker, Keycloak, nginx).

> Las versiones son las **reales** del proyecto. Las de Spring las fija el `spring-boot-starter-parent` 3.5.0 y las de Spring Cloud el BOM 2025.0.0 (ver [codigo/01-proyecto-maven.md](codigo/01-proyecto-maven.md)).

---

## Mapa: qué tecnología usa cada pieza

```
                         ┌───────────────────────────────────────────┐
  Navegador ───────────► │ frontend      nginx 1.27.3 · HTML/CSS/JS   │
  (keycloak-js 26.0.7)   │               keycloak-js (login PKCE)    │
                         └──────────────┬────────────────────────────┘
                                        │ /api (proxy)
  Postman / Newman ─────────────────────▼
                         ┌───────────────────────────────────────────┐
                         │ api-gateway   Spring Cloud Gateway 4.3.0   │
                         │               WebFlux + Netty · OAuth2 RS │
                         │               springdoc webflux (Swagger) │
                         └──────────────┬────────────────────────────┘
              ┌─────────────────────────┼──────────────────────────┐
              ▼                         ▼                          ▼
   ms-clientes              ms-tecnicos               ms-solicitudes
   Spring Boot 3.5.0        Spring Boot 3.5.0          Spring Boot 3.5.0
   MVC + Tomcat             MVC + Tomcat               MVC + Tomcat
   JPA/Hibernate + H2       JPA/Hibernate + H2         JPA/Hibernate + H2
   Security (OAuth2 RS)     Security (OAuth2 RS)       Security (RS + OAuth2 client)
   springdoc · Actuator     commons-geo (Haversine)    Resilience4j · RestClient
                                                       commons-geo (OSRM) · @Scheduled
              └─────────────── Keycloak 26.0.7 (tokens JWT) ───────────────┘
   Todo corre en Docker 29 + Docker Compose sobre eclipse-temurin 21 (Java)
```

---

## 1. Lenguaje, compilación y base

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **Java** (OpenJDK, Eclipse Temurin) | 21 (LTS) | El lenguaje de programación del backend | Todo el código de los microservicios, el gateway y commons-geo. Usamos cosas modernas de Java 21: `record`, *pattern matching* (`instanceof X x`), `List.getFirst()` |
| **Apache Maven** | 3.9.16 | Herramienta de construcción y gestión de dependencias | Baja las librerías, compila y arma los `.jar`. Proyecto **multi-módulo** (padre + 5 módulos) |
| **Lombok** | 1.18.38 | Librería que genera código al compilar | `@Data` (getters y setters), `@Builder`, constructores. Menos código repetido en las entidades |

## 2. Frameworks del backend

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **Spring Boot** | 3.5.0 | Framework para crear aplicaciones Java listas para ejecutar | La base de los 4 servicios Java: arranque, configuración (`application.yml`), inyección de dependencias, auto-configuración |
| **Spring Framework** | 6.2.7 | El núcleo sobre el que está hecho Spring Boot | Beans, `@Transactional`, `@Scheduled`, AOP (proxies) |
| **Spring Web MVC** (+ Apache **Tomcat** embebido) | 6.2.7 (+ Tomcat 10.1.41) | Framework web "clásico": un hilo por pedido | Los endpoints REST de los 3 microservicios (`@RestController`, `@GetMapping`…) y `RestClient` para llamar a otros servicios |
| **Spring Cloud Gateway** (+ **Spring WebFlux** y **Netty**) | 4.3.0 (Spring Cloud 2025.0.0) | API Gateway reactivo | La puerta de entrada: rutas (`RouteLocator`), filtros y validación del token. WebFlux = modelo reactivo, con pocos hilos para muchos pedidos |
| **Spring Data JPA** | 3.5.0 | Simplifica el acceso a datos con JPA | Los `Repository` con consultas por nombre (`existsByDni`) y `@Query` |
| **Hibernate ORM** | 6.6.15 | Implementación de JPA (el "traductor" objeto ↔ tabla) | Genera el SQL, crea las tablas (`ddl-auto: update`), *dirty checking*, `@Version` |
| **Jakarta Persistence (JPA)** | 3.1 | El estándar de persistencia de Java | Las anotaciones `@Entity`, `@Id`, `@ManyToOne`, `@ElementCollection` |
| **Hibernate Validator** (Bean Validation) | 8.0.2 | Validación declarativa | `@NotBlank`, `@NotNull`, `@Pattern`, `@DecimalMin`, `@Valid` en los DTOs → errores 400 |
| **Jackson** | 2.19.0 | Convertidor JSON ↔ objetos Java | Lee los cuerpos de los pedidos y escribe las respuestas; lee las respuestas de otros servicios |

## 3. Seguridad

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **Keycloak** | 26.0.7 | Servidor de identidad (Identity Provider) OIDC / OAuth 2.0 | Guarda usuarios y roles (realm `patito`) y emite los tokens JWT. Corre en Docker |
| **Spring Security** | 6.5.0 | Framework de seguridad de Spring | Cadena de filtros, `@PreAuthorize`, respuestas 401 y 403 |
| **OAuth2 Resource Server** (Spring Security + **Nimbus JOSE+JWT** 9.37.3) | 6.5.0 | Validación de tokens JWT | Gateway y microservicios validan firma, vencimiento e issuer del token |
| **OAuth2 Client** (Spring Security) | 6.5.0 | Cliente OAuth 2.0 | La **cuenta de servicio** de ms-solicitudes (`client_credentials`) para el scheduler |
| **JWT** / **OIDC** / **OAuth 2.0** | estándares | Formatos y protocolos | Token firmado con los roles (`realm_access.roles`) y los claims `clienteId`/`tecnicoId`. Flujos: *password* (Postman), *authorization code + PKCE* (frontend), *client credentials* (servicio) |

## 4. Comunicación entre servicios y resiliencia

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **RestClient** (Spring) | 6.2.7 | Cliente HTTP sincrónico de Spring | ms-solicitudes llama a ms-clientes y ms-tecnicos; commons-geo llama a OSRM. Con timeouts e interceptores |
| **Resilience4j** | 2.2.0 | Librería de tolerancia a fallos | `@Retry` (reintentar una vez) y `@CircuitBreaker` (cortar si un servicio falla mucho) → 503 |
| **Spring AOP** | 6.2.7 | Programación orientada a aspectos | Resilience4j la usa para "envolver" los métodos anotados |
| **OSRM** (Open Source Routing Machine), servidor público | API v1 | Servicio de mapas que calcula rutas por calle | Distancia y tiempo **reales** para el costo de visita (RN-8). Si no responde se usa **Haversine** (fórmula local) |

## 5. Documentación de la API

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **springdoc-openapi** (webmvc-ui y webflux-ui) | 2.8.9 | Genera la documentación OpenAPI leyendo los controllers | `/v3/api-docs` en cada servicio y el Swagger **unificado** del gateway |
| **Swagger UI** | 5.21.0 | La página web interactiva de la documentación | Probar los endpoints desde el navegador (botón **Authorize** para el token) |
| **OpenAPI** / **Swagger Annotations** (swagger-core) | 3.0 / 2.2.30 | Estándar de descripción de APIs y sus anotaciones | `@Operation`, `@ApiResponses`, `@Schema`, `@Tag` |

## 6. Base de datos

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **H2 Database** | 2.3.232 | Base de datos relacional escrita en Java, liviana | Una base **por microservicio** (*database per service*), **en archivo** (`jdbc:h2:file:...`). En Docker vive en un volumen. Consola web en `/h2-console` |

## 7. Logs, trazabilidad y monitoreo

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **SLF4J** | 2.0.17 | La "fachada" de logging (la interfaz con la que se escribe el log) | `log.info("... {}", valor)` en todas las clases |
| **Logback** | 1.5.18 | La implementación que realmente escribe los logs | Formato con `[nombre-servicio] [cid:...]`, salida a consola (stdout) |
| **MDC** (de SLF4J) | — | "Mochila" de datos por hilo | Guarda el `correlationId` para que aparezca en cada línea |
| **Spring Boot Actuator** | 3.5.0 | Endpoints de monitoreo | `/actuator/health` (healthchecks de Docker), `info`, `metrics`, `loggers` (cambiar el nivel en caliente) |
| **Micrometer** | 1.15.0 | Librería de métricas | Las métricas que expone `/actuator/metrics` (y las de Resilience4j) |

## 8. Infraestructura y despliegue

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **Docker** | 29.9 | Contenedores | Una imagen por servicio (Dockerfile en cada módulo) |
| **Docker Compose** | 5.7 | Orquestación de varios contenedores | `docker-compose.yml` (red, volúmenes, healthchecks, `depends_on`) + `docker-compose.override.yml` para desarrollo |
| **Eclipse Temurin JRE** (imagen) | 21.0.8_9 | Java listo para ejecutar, sobre Ubuntu | Imagen base de los 4 servicios Java |
| **Keycloak** (imagen oficial `quay.io/keycloak/keycloak`) | 26.0.7 | — | El servidor de identidad, en modo `start-dev` con importación del realm |
| **nginx** (imagen `nginx:alpine`) | 1.27.3 | Servidor web y proxy reverso | Sirve el frontend y reenvía `/api` al gateway |
| **Fedora Linux** + **SELinux** | — | El sistema donde se desarrolló | Por SELinux, el volumen del realm lleva la opción `:z` |

## 9. Frontend

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **HTML5 + CSS3 + JavaScript** (ES2022, módulos) | — | Los lenguajes de la web, **sin framework** (no hay React ni Angular) | La página: pestañas, formularios, gráficos de barras del informe hechos con CSS |
| **keycloak-js** | 26.0.7 | Librería oficial de Keycloak para navegadores (desde el CDN jsDelivr) | Login con *Authorization Code + PKCE*, renovación del token, logout |
| **Fetch API** | estándar del navegador | Pedidos HTTP desde JavaScript | Todas las llamadas al backend con `Authorization: Bearer` |

## 10. Pruebas y herramientas

| Tecnología | Versión | Qué es | Para qué la usamos |
|---|---|---|---|
| **Postman** | — | Cliente para probar APIs | Colección "Técnica Patito – Backend" (00 a 06) con tests automáticos en JavaScript (`pm.test`, Chai) |
| **Newman** | 6.2.1 | Postman por línea de comandos | Correr la colección entera y ver cuántos tests pasan (`npx newman run ...`) |
| **curl** | — | Cliente HTTP de terminal | Pruebas rápidas y healthchecks dentro de los contenedores |
| **Visual Studio Code** | — | Editor | Desarrollo (extensiones de Java y de Docker/Container Tools) |
| **Git** + **GitHub** | — | Control de versiones | Repositorio del proyecto |
| **PlantUML** / **Mermaid** | — | Diagramas como texto | C4, DER y estados del PDF; diagramas del README |

---

## ¿Por qué estas y no otras?

| Decisión | Motivo |
|---|---|
| **Spring Boot 3.5 + Java 21** | Lo piden los lineamientos de la cátedra (§1). Java 21 es LTS (soporte largo) |
| **MVC en los microservicios, WebFlux solo en el gateway** | Lineamiento §1: microservicios bloqueantes (más simples). El gateway de Spring Cloud es reactivo por diseño |
| **RestClient** (y no RestTemplate ni WebClient) | Lineamiento §6: RestTemplate es el viejo; WebClient es para aplicaciones reactivas |
| **Keycloak** y no JWT "hecho a mano" | Lineamiento §8: no implementar la seguridad a mano |
| **Resilience4j** | Es la herramienta que nombra el lineamiento §6 |
| **H2 en archivo** | Decisión del equipo: los datos sobreviven a los reinicios. El lineamiento la acepta (memoria) y menciona PostgreSQL/MariaDB como alternativas |
| **Imagen `eclipse-temurin`** y no `openjdk` | Las imágenes `openjdk` de Docker Hub están discontinuadas; Temurin es la distribución recomendada |
| **Frontend sin framework** | El PDF dejaba el frontend fuera del alcance; se agregó solo para la demo. Sin framework no hay que compilar nada ni instalar Node |
| **OSRM público + Haversine** | No necesita una API key (Google Maps sí). Haversine es el respaldo que pide RN-8 |
