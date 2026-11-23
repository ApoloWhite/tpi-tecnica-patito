# TPI – Técnica Patito S.R.L.

Backend de Aplicaciones – UTN FRC – 3K1 – GRUPO-107

Backend de microservicios para una empresa de servicios técnicos domiciliarios de Córdoba. Gestiona el ciclo de vida completo de una solicitud: el cliente la crea, el sistema busca al técnico más cercano con la certificación necesaria, le envía una oferta con 15 minutos para aceptar (y si rechaza o no contesta, pasa solo al siguiente), el técnico carga el presupuesto, el cliente lo aprueba y el trabajo se finaliza con el costo de visita calculado. Todo queda registrado en un historial de estados.

Incluye un **frontend web** (http://localhost:47300) para usar el sistema con los tres roles.

> ¿Primera vez? Empezá por el [índice de la documentación](docs/README.md):
> - [Accesos](docs/ACCESOS.md): todos los enlaces, usuarios y contraseñas (Keycloak, H2, Swagger, frontend).
> - [Guía paso a paso](docs/GUIA-PASO-A-PASO.md): cómo levantar todo y usar el frontend, Swagger y Postman.
> - [Cómo funciona](docs/COMO-FUNCIONA.md): el viaje de una petición por dentro y un diccionario de conceptos.
> - [El código explicado](docs/codigo/): cada módulo, archivo por archivo y línea por línea.

## Tecnologías y frameworks

| Capa | Tecnologías |
|---|---|
| Lenguaje y build | **Java 21** (Eclipse Temurin) · **Maven 3.9** (multi-módulo) · Lombok 1.18 |
| Backend | **Spring Boot 3.5.0** (Spring Framework 6.2) · Spring Web MVC + Tomcat · Spring Data JPA + **Hibernate 6.6** · Bean Validation · Jackson |
| API Gateway | **Spring Cloud Gateway 4.3** (Spring Cloud 2025.0.0) · WebFlux + Netty |
| Seguridad | **Keycloak 26.0.7** (OIDC / OAuth 2.0, JWT) · **Spring Security 6.5** (Resource Server + OAuth2 Client) |
| Comunicación y resiliencia | Spring **RestClient** · **Resilience4j 2.2** (retry + circuit breaker) · OSRM (mapas) + Haversine |
| Base de datos | **H2 2.3** en archivo (una por microservicio) |
| Documentación | **springdoc-openapi 2.8.9** · Swagger UI 5.21 (OpenAPI 3) |
| Logs y monitoreo | SLF4J + Logback · MDC (correlation id) · Spring Boot Actuator · Micrometer |
| Infraestructura | **Docker** + **Docker Compose** · nginx 1.27 |
| Frontend | HTML + CSS + JavaScript (sin framework) · keycloak-js 26.0.7 |
| Pruebas | **Postman** + Newman 6.2 |

Detalle de cada una (versión, para qué se usa y por qué se eligió): [docs/TECNOLOGIAS.md](docs/TECNOLOGIAS.md).

## Arquitectura

```mermaid
flowchart LR
    U[Cliente / Técnico / Admin<br/>Postman o Swagger] -->|1. pide token| KC[Keycloak :47390<br/>realm patito]
    U -->|2. Bearer token| GW[API Gateway :47380<br/>valida JWT · 401]
    W[Frontend :47300<br/>nginx] -->|login PKCE| KC
    W -->|/api proxy| GW
    GW --> MC[ms-clientes :47383]
    GW --> MT[ms-tecnicos :47382]
    GW --> MS[ms-solicitudes :47381]
    MS -->|REST + token del usuario<br/>o de la cuenta de servicio| MC
    MS -->|REST + token| MT
    MS -.->|commons-geo| OSRM[(OSRM<br/>API de mapas)]
    MT -.->|commons-geo<br/>Haversine| MT
    MC --- DB1[(H2 clientes)]
    MT --- DB2[(H2 técnicos)]
    MS --- DB3[(H2 solicitudes)]
```

| Módulo | Puerto | Responsabilidad |
|---|---|---|
| `api-gateway` | 47380 | Único punto de entrada. Valida el JWT (401), rutea, genera el `X-Correlation-ID` y publica el Swagger unificado |
| `ms-solicitudes` | 47381 | Ciclo de vida de la solicitud, estados, ventana de 15 min, RN-5, costo de visita (Strategy) |
| `ms-tecnicos` | 47382 | Padrón de técnicos, certificaciones, zonas y búsqueda de candidatos (< 13 km) |
| `ms-clientes` | 47383 | Padrón de clientes con domicilio y coordenadas |
| `commons-geo` | — | Librería: Haversine + cliente OSRM con timeouts y respaldo |
| Keycloak | 47390 | Emite los tokens JWT (realm `patito`) |
| `frontend` | 47300 | Página web (HTML + JS) servida por nginx; reenvía `/api` al gateway |

Cada microservicio está organizado en `controllers`, `services`, `repositories`, `models`, `dtos`, `exceptions` y `config`.

**Seguridad en dos niveles:**
1. El **gateway** rechaza con **401** cualquier petición a `/api/**` sin un token válido.
2. Cada **microservicio** también valida el token y decide con `@PreAuthorize` si el rol puede hacer la operación. Si no puede, responde **403**.

## Modo de asignación: automático o manual

Cada solicitud se crea con `"modoAsignacion"`:

| Modo | Qué pasa |
|---|---|
| `AUTOMATICA` (por defecto) | Al crearla, el **sistema** la oferta solo al técnico candidato más cercano (F5). Si ese técnico rechaza, deja vencer la oferta o la deriva, el sistema la oferta al **siguiente**, sin repetir a los que ya la dejaron (RN-3). Si no queda nadie, queda PENDIENTE y se alerta al administrador (RN-2) |
| `MANUAL` | El cliente o el administrador eligen: `POST /elegir` muestra los candidatos con su costo y `POST /ofertar/{tecnicoId}` envía la oferta. Si el técnico rechaza o vence, vuelve a PENDIENTE y se elige de nuevo |

Cuando la oferta vence, el que actúa es el scheduler y no hay ningún usuario con token. Para poder consultar a ms-tecnicos, ms-solicitudes usa su propia **cuenta de servicio** en Keycloak: client `ms-solicitudes`, grant `client_credentials`, rol `SISTEMA`. El secreto llega por la variable `MS_SOLICITUDES_CLIENT_SECRET`.

## Cómo levantar todo

### Opción A: Docker (recomendada)

Requisitos: Java 21, Maven 3.9+, Docker con Compose. Los puertos 47380 a 47383 y 47390 tienen que estar libres.

```bash
# 1. Compilar y generar los JAR (los Dockerfile copian el JAR de target/)
mvn clean package -DskipTests

# 2. Construir las imágenes y levantar todo
docker compose up -d --build

# 3. Esperar a que todos estén "healthy" (tarda ~1 minuto)
docker compose ps
```

- **Desarrollo** (`docker compose up`): también aplica `docker-compose.override.yml` y publica los puertos de los microservicios (47381, 47382, 47383) para entrar a su Swagger y a la consola H2.
- **Producción** (`docker compose --file docker-compose.yml up -d --build`): solo publica el gateway (47380), Keycloak (47390) y el frontend (47300).

Cuando todo está `healthy`, entrá a **http://localhost:47300** e iniciá sesión con cualquiera de los usuarios de abajo.

> Si cambiás `keycloak/realm-export.json`, Keycloak solo lo vuelve a leer al recrear el contenedor: `docker compose up -d --force-recreate keycloak`.

Para apagar: `docker compose down`. Para borrar también las bases y empezar de cero: `docker compose down -v`.

Variables opcionales: copiá `.env.example` como `.env`. La más útil es `OFERTA_VENTANA_MINUTOS=1`, para probar el vencimiento de ofertas (EN-07) sin esperar 15 minutos.

### Opción B: local, sin Docker para los microservicios

```bash
docker compose up -d keycloak          # solo Keycloak
mvn clean install -DskipTests
# una terminal por servicio, en este orden:
mvn -pl ms-clientes spring-boot:run
mvn -pl ms-tecnicos spring-boot:run
mvn -pl ms-solicitudes spring-boot:run
mvn -pl api-gateway spring-boot:run
```

No mezcles las dos opciones: usan los mismos puertos.

## Usuarios de prueba (Keycloak)

| Usuario | Contraseña | Rol |
|---|---|---|
| `admin1` | `admin123` | ADMINISTRADOR |
| `cliente1` | `cliente123` | CLIENTE (es el cliente nº 1, Carlos García) |
| `tecnico1` | `tecnico123` | TECNICO (es el técnico nº 1, Juan Pérez) |
| `tecnico2` | `tecnico123` | TECNICO (es la técnica nº 2, Ana Gómez) |

Cada usuario está **vinculado a su registro del padrón**: Keycloak guarda los atributos `clienteId` y `tecnicoId` y los manda dentro del token. El frontend los lee para mostrarle a cada uno **solo lo suyo**.

Client: `bda-client` (público, con *Direct Access Grants* para Postman y Authorization Code + PKCE para el frontend). Client `ms-solicitudes`: cuenta de servicio, rol `SISTEMA`. Consola de Keycloak: http://localhost:47390 (usuario `admin` / `admin`).

## Documentación Swagger

| Qué | URL |
|---|---|
| Swagger unificado (los 3 servicios, a través del gateway) | http://localhost:47380/swagger-ui.html |
| MS-Solicitudes directo (solo en desarrollo) | http://localhost:47381/swagger-ui.html |
| MS-Técnicos directo | http://localhost:47382/swagger-ui.html |
| MS-Clientes directo | http://localhost:47383/swagger-ui.html |
| Consola H2 (ej. clientes) | http://localhost:47383/h2-console (JDBC URL `jdbc:h2:file:/app/data/clientes-db;AUTO_SERVER=TRUE`, usuario `sa`, sin clave) |

En Swagger, el botón **Authorize** pide el token: pegá el `access_token` de Keycloak, sin la palabra `Bearer`.

## Colección de Postman

Archivos en [`postman/`](postman/):
- `Tecnica-Patito.postman_collection.json`: la colección (83 peticiones con tests automáticos).
- `Tecnica-Patito.postman_environment.json`: el entorno "Técnica Patito – Local".

| Carpeta | Qué prueba |
|---|---|
| 00 – Seguridad | Obtiene los 3 tokens (correr siempre primero) |
| 01 – Clientes | Listar, consultar, registrar, modificar y eliminar clientes |
| 02 – Técnicos | Listar, consultar, candidatos (< 13 km), catálogos, registrar, disponibilidad |
| 03 – Solicitudes | Listado, estrategias de costo, patrón snapshot, rechazo de oferta |
| 04 – Flujo principal (modo manual) | EP-01 a EP-05: crear → elegir → ofertar → aceptar → presupuesto → aprobar → finalizar → historial |
| 04B – Flujo principal (modo automático) | La solicitud nace OFERTADA al más cercano; Juan rechaza y el sistema la oferta sola a Ana; y el caso en que no queda ningún candidato |
| 05 – Escenarios negativos | EN-01 a EN-06, EN-08 y EN-10 |
| 06 – Escenarios manuales | EN-07 (oferta vencida), EN-07B (vencimiento con reoferta automática) y EN-09 (servicio caído): necesitan una acción fuera de Postman |

Las carpetas 00 a 05 (incluida la 04B) se pueden correr enteras con el *Runner*, todas las veces que quieras: los escenarios limpian lo que crean.

Por línea de comandos: `npx newman run postman/Tecnica-Patito.postman_collection.json -e postman/Tecnica-Patito.postman_environment.json --folder "00 – Seguridad" --folder "01 – Clientes" --folder "02 – Técnicos" --folder "03 – Solicitudes" --folder "04 – Flujo principal (modo manual)" --folder "04B – Flujo principal (modo automático)" --folder "05 – Escenarios negativos"`

## Endpoints

Todos a través de `http://localhost:47380`. Los roles son los que acepta `@PreAuthorize`.

### MS-Clientes
| Método | Endpoint | Operación | Roles |
|---|---|---|---|
| POST | `/api/v1/clientes` | Registrar un cliente | ADMINISTRADOR, CLIENTE |
| GET | `/api/v1/clientes?apellido=&page=&size=` | Listar clientes (paginado) | ADMINISTRADOR |
| GET | `/api/v1/clientes/{clienteId}` | Consultar un cliente con coordenadas | ADMINISTRADOR, CLIENTE |
| PUT | `/api/v1/clientes/{clienteId}` | Modificar un cliente | ADMINISTRADOR, CLIENTE |
| DELETE | `/api/v1/clientes/{clienteId}` | Eliminar un cliente | ADMINISTRADOR |

### MS-Técnicos
| Método | Endpoint | Operación | Roles |
|---|---|---|---|
| POST | `/api/v1/tecnicos` | Registrar técnico con certificaciones y zonas | ADMINISTRADOR |
| GET | `/api/v1/tecnicos?disponible=&page=&size=` | Listar técnicos (paginado) | todos |
| GET | `/api/v1/tecnicos/{tecnicoId}` | Perfil, disponibilidad y certificaciones | todos |
| GET | `/api/v1/tecnicos/candidatos?certificacionId=&latitud=&longitud=` | Disponibles, con la certificación y a < 13 km | todos |
| PATCH | `/api/v1/tecnicos/{tecnicoId}/disponibilidad` | Cambiar disponibilidad | ADMINISTRADOR, TECNICO |
| GET | `/api/v1/certificaciones` | Catálogo de certificaciones | todos |
| GET | `/api/v1/zonas` | Catálogo de zonas | todos |

### MS-Solicitudes
| Método | Endpoint | Operación | Roles |
|---|---|---|---|
| POST | `/api/v1/solicitudes` | Crear solicitud copiando los datos del cliente. En modo AUTOMATICA se oferta sola al más cercano | ADMINISTRADOR, CLIENTE |
| GET | `/api/v1/solicitudes?estado=&page=&size=` | Listar solicitudes (paginado) | todos |
| GET | `/api/v1/solicitudes/{solicitudId}` | Detalle y estado actual | todos |
| GET | `/api/v1/solicitudes/{solicitudId}/historial` | Historial de estados (RN-7) | todos |
| GET | `/api/v1/solicitudes/informe?desde=&hasta=` | Informe de gestión: estados, ofertas, estrategias, facturación y desempeño por técnico | ADMINISTRADOR |
| POST | `/api/v1/solicitudes/elegir` | Probar candidatos y costo antes de ofertar | ADMINISTRADOR, CLIENTE |
| POST | `/api/v1/solicitudes/{solicitudId}/ofertar/{tecnicoId}` | Enviar oferta → OFERTADA | ADMINISTRADOR, CLIENTE |
| POST | `/api/v1/solicitudes/{solicitudId}/aceptar/{tecnicoId}` | Aceptar oferta y calcular costo → ASIGNADA | ADMINISTRADOR, TECNICO |
| POST | `/api/v1/solicitudes/{solicitudId}/rechazar/{tecnicoId}` | Rechazar oferta → PENDIENTE | ADMINISTRADOR, TECNICO |
| POST | `/api/v1/solicitudes/{solicitudId}/presupuesto` | Cargar diagnóstico y presupuesto | ADMINISTRADOR, TECNICO |
| POST | `/api/v1/solicitudes/{solicitudId}/aprobar-presupuesto` | Aprobar presupuesto → EN_CURSO | ADMINISTRADOR, CLIENTE |
| POST | `/api/v1/solicitudes/{solicitudId}/cancelar?motivo=` | Cancelar (por ejemplo, presupuesto rechazado) | ADMINISTRADOR, CLIENTE |
| POST | `/api/v1/solicitudes/{solicitudId}/reprogramar?motivo=` | Falta de repuesto → REPROGRAMADA | ADMINISTRADOR, TECNICO |
| POST | `/api/v1/solicitudes/{solicitudId}/reanudar` | REPROGRAMADA → EN_CURSO | ADMINISTRADOR, TECNICO |
| POST | `/api/v1/solicitudes/{solicitudId}/derivar?motivo=` | EN_CURSO → DERIVADA → PENDIENTE | ADMINISTRADOR, TECNICO |
| POST | `/api/v1/solicitudes/{solicitudId}/finalizar` | Finalizar → FINALIZADA | ADMINISTRADOR, TECNICO |

## Estados de una solicitud

```mermaid
stateDiagram-v2
    [*] --> PENDIENTE : crear
    PENDIENTE --> OFERTADA : ofertar
    OFERTADA --> ASIGNADA : técnico acepta
    OFERTADA --> PENDIENTE : rechaza o vencen 15 min
    ASIGNADA --> EN_CURSO : cliente aprueba presupuesto
    PENDIENTE --> CANCELADA
    OFERTADA --> CANCELADA
    ASIGNADA --> CANCELADA : cliente rechaza presupuesto
    EN_CURSO --> FINALIZADA : finalizar
    EN_CURSO --> REPROGRAMADA : falta repuesto
    REPROGRAMADA --> EN_CURSO : reanudar
    EN_CURSO --> DERIVADA : supera certificación
    DERIVADA --> PENDIENTE
    CANCELADA --> [*]
    FINALIZADA --> [*]
```

## Reglas de negocio implementadas

| Regla | Dónde | Cómo |
|---|---|---|
| RN-1 Tipos de servicio | `SolicitudRequest` | Bean Validation: marca, modelo, descripción y al menos un tipo. Si falta algo → 400 con el error de cada campo |
| RN-2 Candidatura | `TecnicoService.buscarCandidatos` | Disponible + certificación + radio. Sin candidatos → queda PENDIENTE, se anota en el historial y se loguea `ALERTA ADMINISTRADOR` |
| RN-3 Ventana de 15 min | `OfertaScheduler` + `SolicitudService.expirarOferta` | `@Scheduled` + `@Transactional` por solicitud; en modo AUTOMATICA reoferta al siguiente; configurable con `OFERTA_VENTANA_MINUTOS` |
| F5 Asignación automática | `SolicitudService.ofertarAutomaticamente` | Al crear (y tras rechazo, vencimiento o derivación) oferta al candidato más cercano no descartado y sin límite (RN-5) |
| RN-4 Un técnico por solicitud | `SolicitudService` + `@Version` | Solo el técnico ofertado responde; bloqueo optimista ante concurrencia |
| RN-5 Límite por técnico | `SolicitudService` | Máximo 2 en espera (OFERTADA/ASIGNADA/REPROGRAMADA) y 1 EN_CURSO → 422 |
| RN-6 Aprobación de presupuesto | `SolicitudService.aprobarPresupuesto` | Solo desde ASIGNADA y con presupuesto cargado → si no, 422 |
| RN-7 Estados e historial | `EstadoSolicitud` + `HistorialEstado` | Mapa de transiciones válidas; cada cambio guarda fecha, hora, usuario y observación |
| RN-8 Costo de visita | `CalculadoraCosto` + `commons-geo` | Monto fijo + km × tarifa; distancia real con OSRM y, si falla, Haversine |
| RN-9 Cercanía | `commons-geo` `Haversine` | Radio de 13 km (`app.candidatos.radio-km`) |
| RN-10 Snapshot *(definida por el equipo)* | `SolicitudService.crear/ofertar` | Se copian nombre, domicilio y coordenadas del cliente, y el nombre del técnico |
| RN-11 Estrategias de costo *(definida por el equipo)* | `services/costos` (patrón Strategy) | ESTANDAR, PRIORIDAD (+30 %), FUERA_HORARIO (+50 %, fines de semana, feriados, antes de las 8 o desde las 18) y COMBINADA |

Tarifas en `ms-solicitudes/src/main/resources/application.yml` (`tarifas.*`): monto fijo $5000 y $800 por km.

## Errores (ProblemDetail, RFC 7807)

| Código | Cuándo |
|---|---|
| 400 | Faltan datos o tienen formato inválido (incluye `errores` con el problema de cada campo) |
| 401 | Sin token o con un token inválido o vencido |
| 403 | El rol no tiene permiso |
| 404 | El recurso no existe |
| 422 | Se incumple una regla de negocio (DNI duplicado, transición inválida, RN-5, etc.) |
| 503 | Un microservicio del que se depende está caído (reintento + circuit breaker de Resilience4j) |

Todas las respuestas de error traen `correlationId`: buscándolo en los logs se sigue la petición por todos los servicios.

## Logs y monitoreo

- Logs a stdout con el formato `[nombre-servicio] [cid:<correlationId>]`. En Docker: `docker compose logs -f ms-solicitudes`.
- El gateway genera `X-Correlation-ID` si no viene en la petición, y cada servicio lo propaga en sus llamadas salientes.
- Actuator: `/actuator/health` es público; `info`, `metrics` y `loggers` requieren el rol ADMINISTRADOR. Ejemplo para cambiar un nivel de log en caliente: `POST /actuator/loggers/utnfc.isi.back.tpi` con `{"configuredLevel":"TRACE"}`.

## Decisiones del equipo

| Tema | Decisión | Motivo |
|---|---|---|
| Rutas | `/api/v1/...` | Lineamiento de la cátedra (versionado en la URI). El formulario del TPI decía `/api/...` |
| Base de datos | H2 **en archivo**, `ddl-auto: update`; en Docker, un volumen nombrado por servicio | Elección del equipo: los datos sobreviven a los reinicios. El DataLoader solo carga si la base está vacía |
| Versiones | Java 21, Spring Boot 3.5.0, Spring Cloud 2025.0.0, springdoc 2.8.9, Resilience4j 2.2.0 | Lineamientos de la cátedra (el formulario decía Java 25 y Boot 3.2.5) |
| Imagen base | `eclipse-temurin:21.0.8_9-jre` | Versión fija; las imágenes `openjdk` están discontinuadas |
| Domicilio | Calle y barrio como campos de `Cliente` | El DER solo tiene la entidad CLIENTE |
| Presupuesto | Nuevo `POST /{id}/presupuesto` | F7 pide que el técnico cargue el presupuesto y no había endpoint |
| Mapa | OSRM público con respaldo Haversine | Sin contenedor OSRM: requiere internet para la ruta real; sin internet, la distancia sale por Haversine |
| Zonas de cobertura | Informativas; la cobertura efectiva es el radio de 13 km | No hay geometría de zonas en el DER |
| Asignación | Automática por defecto, manual opcional (`modoAsignacion`) | F5 y RN-3 piden que el sistema oferte solo; el modo manual conserva los endpoints `/elegir` y `/ofertar` del PDF |
| Llamadas sin usuario | Cuenta de servicio `ms-solicitudes` (client_credentials, rol `SISTEMA`) | El scheduler no tiene token de usuario para consultar a ms-tecnicos |
| Puertos | Rango 473xx: frontend 47300, gateway 47380, ms 47381-47383, Keycloak 47390 | Los puertos típicos (8080, 3000, 9090) chocan con otros programas. El formulario del TPI decía 8080-8083 y 9090 |
| Usuarios ↔ padrón | Atributos `clienteId`/`tecnicoId` en Keycloak, enviados como claims del token | El frontend no deja elegir identidad: el cliente ve solo sus datos y sus solicitudes, y el técnico solo sus trabajos |
| Frontend | HTML + JS sin framework, keycloak-js 26.0.7, nginx 1.27.3 | El PDF lo dejaba fuera del alcance; se agregó para la demostración. Usa el flujo Authorization Code + PKCE que recomienda la cátedra |
