# 01 – El proyecto Maven: la caja de herramientas

## ¿Qué es Maven? (explicado fácil)

Imaginá que querés armar 5 juguetes de Lego, y cada uno necesita piezas distintas. **Maven** es el ayudante que:

1. lee la **lista de piezas** de cada juguete (el archivo `pom.xml`);
2. va a la juguetería de internet (Maven Central) y trae las piezas que faltan (las **dependencias**, que son librerías `.jar`);
3. arma cada juguete (**compila** el código) y lo mete en una caja (el **`.jar` final** de `target/`).

Nuestro proyecto es **multi-módulo**: una caja grande (el proyecto padre) con 5 cajas adentro.

```
tpi-tecnica-patito/            ← proyecto padre (pom.xml raíz)
├── commons-geo/               ← librería compartida (no se ejecuta sola)
├── ms-clientes/               ← microservicio  → ms-clientes-1.0.0.jar
├── ms-tecnicos/               ← microservicio  → ms-tecnicos-1.0.0.jar
├── ms-solicitudes/            ← microservicio  → ms-solicitudes-1.0.0.jar
└── api-gateway/               ← gateway        → api-gateway-1.0.0.jar
```

Comandos que vas a usar:

| Comando | Qué hace |
|---|---|
| `mvn clean package -DskipTests` | Borra lo compilado antes (`clean`), compila todo y genera los `.jar` en cada `target/`. `-DskipTests` saltea los tests |
| `mvn clean install -DskipTests` | Igual, pero además copia los `.jar` a tu repositorio local (`~/.m2`). Hace falta para correr un módulo suelto que usa `commons-geo` |
| `mvn -pl ms-clientes spring-boot:run` | Ejecuta un solo módulo (`-pl` = *project list*) |

---

## El `pom.xml` raíz (el padre)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.0</version>
    <relativePath/>
</parent>
```
- `<parent>`: "heredo la configuración de otro pom". Es como heredar de una clase en Java.
- `spring-boot-starter-parent` versión `3.5.0`: el pom oficial de Spring Boot. Trae las **versiones probadas** de cientos de librerías, así no tenemos que escribir la versión de cada una, y configura el compilador para Java.
- `<relativePath/>` vacío: "no busques el padre en una carpeta de al lado, bajalo de internet".

```xml
<groupId>utnfc.isi.back.tpi</groupId>
<artifactId>tpi-tecnica-patito</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>
<name>TPI Tecnica Patito</name>
```
- `groupId` + `artifactId` + `version`: el **DNI** del proyecto (las "coordenadas" Maven). `groupId` es como el apellido de la familia, `artifactId` el nombre propio.
- `<packaging>pom</packaging>`: este proyecto **no genera un `.jar`**. Solo agrupa a los módulos de adentro.

```xml
<properties>
    <java.version>21</java.version>
    <springdoc.version>2.8.9</springdoc.version>
    <spring-cloud.version>2025.0.0</spring-cloud.version>
    <!-- Misma versión que gestiona el BOM de Spring Cloud 2025.0.0 -->
    <resilience4j.version>2.2.0</resilience4j.version>
</properties>
```
- `<properties>`: variables que se usan más abajo como `${nombre}`.
- `java.version 21`: Spring Boot la lee para compilar con Java 21.
- `springdoc.version`: versión de la librería de Swagger.
- `spring-cloud.version`: la "familia" de versiones de Spring Cloud (el gateway).
- `resilience4j.version 2.2.0`: tiene que ser la **misma** que trae Spring Cloud. Si se mezclan versiones, el programa explota al arrancar (`ClassNotFoundException`). Nos pasó con 2.3.0.

```xml
<modules>
    <module>commons-geo</module>
    <module>ms-clientes</module>
    <module>ms-tecnicos</module>
    <module>ms-solicitudes</module>
    <module>api-gateway</module>
</modules>
```
- La lista de las cajas de adentro. Maven las compila **en orden de dependencias**: primero `commons-geo`, porque ms-tecnicos y ms-solicitudes la necesitan.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>
        <dependency>
            <groupId>utnfc.isi.back.tpi</groupId>
            <artifactId>commons-geo</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```
- `<dependencyManagement>`: **no agrega** librerías. Solo dice "si algún hijo usa esta librería, usá **esta versión**". Así la versión se escribe en un solo lugar.
- `spring-cloud-dependencies` con `type pom` y `scope import`: es un **BOM** (*Bill of Materials*, lista de materiales). Importa de una vez las versiones de todas las librerías de Spring Cloud.
- `resilience4j-spring-boot3`: fijamos su versión para que coincida con la del BOM.
- `commons-geo` con `${project.version}`: nuestra propia librería, con la versión del proyecto (1.0.0).

---

## El `pom.xml` de un microservicio (ejemplo: ms-solicitudes)

```xml
<parent>
    <groupId>utnfc.isi.back.tpi</groupId>
    <artifactId>tpi-tecnica-patito</artifactId>
    <version>1.0.0</version>
</parent>

<artifactId>ms-solicitudes</artifactId>
<name>ms-solicitudes</name>
<description>Orquestador del ciclo de vida de solicitudes</description>
```
- El padre de cada módulo es **nuestro** pom raíz. Así hereda la versión de Java, las propiedades y el `dependencyManagement`.
- No repite `groupId` ni `version`: los hereda. Solo dice cómo se llama (`artifactId`).

### Las dependencias, una por una

Los **starters** de Spring Boot (`spring-boot-starter-*`) son "combos": una sola dependencia que trae todo lo necesario para una función.

| Dependencia | Qué trae | Para qué la usamos |
|---|---|---|
| `spring-boot-starter-web` | Spring MVC + servidor Tomcat + Jackson (JSON) + RestClient | Exponer endpoints REST y llamar a otros servicios |
| `spring-boot-starter-data-jpa` | Spring Data JPA + Hibernate | Guardar y leer de la base de datos con objetos Java |
| `spring-boot-starter-validation` | Hibernate Validator (Bean Validation) | `@NotBlank`, `@NotNull`, `@Valid`, etc. |
| `spring-boot-starter-actuator` | Endpoints de monitoreo | `/actuator/health`, `/metrics`, `/loggers` |
| `spring-boot-starter-security` | Spring Security | Filtros de seguridad y `@PreAuthorize` |
| `spring-boot-starter-oauth2-resource-server` | Validación de JWT | Validar el token de Keycloak |
| `spring-boot-starter-oauth2-client` | Cliente OAuth2 | **Solo en ms-solicitudes**: pedir el token de la cuenta de servicio |
| `resilience4j-spring-boot3` | Retry y circuit breaker | **Solo en ms-solicitudes**: `@Retry` y `@CircuitBreaker` |
| `spring-boot-starter-aop` | Programación orientada a aspectos | Resilience4j la necesita para "envolver" los métodos anotados |
| `h2` con `<scope>runtime</scope>` | El motor de base de datos H2 | `runtime` = no se usa al compilar, solo al ejecutar |
| `springdoc-openapi-starter-webmvc-ui` | Swagger UI | La página `/swagger-ui.html` y el JSON `/v3/api-docs` |
| `lombok` con `<optional>true</optional>` | Generador de código | `@Data`, `@Builder`… `optional` = no se "contagia" a quien dependa de este módulo |
| `commons-geo` | Nuestra librería | Haversine y OSRM (sin versión: la toma del padre) |

> Diferencias entre módulos: **ms-clientes** no tiene oauth2-client, resilience4j, aop ni commons-geo. **ms-tecnicos** tiene commons-geo pero no oauth2-client ni resilience4j. El **api-gateway** usa `spring-cloud-starter-gateway` (que trae **WebFlux**, no MVC), `oauth2-resource-server`, `actuator` y `springdoc-openapi-starter-webflux-ui`.

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </exclude>
                </excludes>
            </configuration>
        </plugin>
    </plugins>
</build>
```
- `spring-boot-maven-plugin`: convierte el `.jar` común en un **jar ejecutable** ("fat jar"): mete adentro todas las librerías, así se corre con un solo `java -jar ms-solicitudes-1.0.0.jar`. También agrega el comando `mvn spring-boot:run`.
- `<excludes>` lombok: Lombok solo trabaja **al compilar** (escribe código por vos). No hace falta meterlo en el jar final.

> **commons-geo no tiene este plugin**: es una librería, no un programa. Su `.jar` es común y se mete adentro del jar de quien la usa.

---

## La estructura de carpetas de un microservicio

```
ms-clientes/
├── Dockerfile                    ← receta para la imagen Docker
├── .dockerignore                 ← qué NO mandar al construir la imagen
├── pom.xml
└── src/main/
    ├── java/utnfc/isi/back/tpi/clientes/
    │   ├── MsClientesApplication.java   ← el main: arranca todo
    │   ├── config/        ← configuración: seguridad, Swagger, filtros, DataLoader
    │   ├── controllers/   ← el mozo: endpoints REST
    │   ├── services/      ← el cocinero: lógica de negocio
    │   ├── repositories/  ← la heladera: acceso a datos
    │   ├── models/        ← entidades JPA (tablas)
    │   ├── dtos/          ← bandejas de entrada y salida de la API
    │   └── exceptions/    ← excepciones propias y el manejador global
    └── resources/
        └── application.yml       ← configuración (puerto, base, Keycloak…)
```

Esta estructura de paquetes es la que piden los lineamientos de la cátedra (§2). Maven sabe que el código va en `src/main/java` y la configuración en `src/main/resources` porque es la convención de siempre: no hace falta decírselo.
