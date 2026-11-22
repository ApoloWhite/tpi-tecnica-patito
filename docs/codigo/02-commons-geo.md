# 02 – commons-geo: la librería del mapa

## ¿Para qué sirve? (explicado fácil)

Dos microservicios necesitan saber **qué tan lejos** está un técnico de un cliente:
- **ms-tecnicos**, para descartar a los que están a más de 13 km (RN-9);
- **ms-solicitudes**, para calcular el costo de la visita según los kilómetros (RN-8).

En vez de escribir el mismo código dos veces, lo ponemos en una **librería compartida**: una "caja de herramientas" que los dos agregan a su `pom.xml`.

Tiene dos formas de medir:

| Herramienta | Cómo mide | Ventaja | Desventaja |
|---|---|---|---|
| **Haversine** | Una fórmula matemática: la distancia "a vuelo de pájaro" sobre una esfera | Instantánea, no necesita internet | No sigue las calles |
| **OSRM** | Le pregunta a una página de mapas por internet cuántos km hay **por las calles** | Distancia y tiempo reales | Puede tardar o no responder |

La regla: **se intenta OSRM y, si falla, se usa Haversine** (es el "plan B" que pide RN-8).

Archivos:

```
commons-geo/src/main/
├── java/utnfc/isi/back/tpi/geo/
│   ├── package-info.java          ← documentación del paquete
│   ├── Ruta.java                  ← el resultado: km, minutos y de dónde salió
│   ├── GeoService.java            ← el contrato (interfaz)
│   ├── Haversine.java             ← la fórmula matemática
│   ├── OsrmGeoService.java        ← el que consulta OSRM (con plan B)
│   └── GeoAutoConfiguration.java  ← se "enchufa" solo en Spring
└── resources/META-INF/spring/
    └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

---

## `package-info.java`

```java
/**
 * Librería común commons-geo.
 * ...
 */
package utnfc.isi.back.tpi.geo;
```
- Es un archivo especial de Java que **solo tiene documentación** del paquete. El comentario `/** ... */` (Javadoc) explica qué hay en la carpeta.
- `package utnfc.isi.back.tpi.geo;`: dice a qué paquete pertenece. No tiene clases.

---

## `Ruta.java`: el resultado

```java
public record Ruta(double kilometros, int minutos, Fuente fuente) {

    public enum Fuente { OSRM, HAVERSINE }
}
```
- `public record Ruta(...)`: un **record** es una clase de "solo datos". Java genera solo el constructor `new Ruta(5.2, 10, Fuente.OSRM)` y los métodos `ruta.kilometros()`, `ruta.minutos()` y `ruta.fuente()`. Los datos no se pueden cambiar después (es **inmutable**).
- `double kilometros`: la distancia con decimales.
- `int minutos`: el tiempo estimado de viaje.
- `Fuente fuente`: de dónde salió el dato.
- `public enum Fuente { OSRM, HAVERSINE }`: un **enum** es una lista cerrada de valores posibles. Solo puede ser `OSRM` o `HAVERSINE`. Así, en la solicitud se guarda `fuenteDistancia = "OSRM"` y se sabe si la distancia fue real o estimada.

---

## `GeoService.java`: el contrato

```java
public interface GeoService {

    Ruta calcularRuta(double latOrigen, double lonOrigen, double latDestino, double lonDestino);
}
```
- `interface`: un **contrato**. Dice **qué** se puede pedir, pero no **cómo** se hace. Es como el enchufe de la pared: cualquier aparato que respete la forma puede conectarse.
- `calcularRuta(...)`: recibe la latitud y la longitud del origen y del destino, y devuelve una `Ruta`.
- **¿Por qué una interfaz?** ms-solicitudes pide un `GeoService` y no sabe (ni le importa) si adentro hay OSRM, Google Maps o un simulador. Si mañana cambiamos de API de mapas, ms-solicitudes no se toca.

---

## `Haversine.java`: la fórmula

```java
public final class Haversine {

    private static final double RADIO_TIERRA_KM = 6371.0;
    private static final double VELOCIDAD_PROMEDIO_KMH = 30.0;

    private Haversine() {
    }
```
- `public final class`: `final` = nadie puede heredar de esta clase.
- `private static final double RADIO_TIERRA_KM = 6371.0;`: una **constante**. `static` = es de la clase, no de cada objeto. `final` = no cambia nunca. 6371 km es el radio promedio de la Tierra.
- `VELOCIDAD_PROMEDIO_KMH = 30.0`: para estimar minutos sin OSRM, suponemos que en la ciudad se va a 30 km/h.
- `private Haversine() { }`: el constructor es **privado**, así nadie puede hacer `new Haversine()`. Es una clase de "herramientas sueltas": todos sus métodos son `static` y se usan como `Haversine.distanciaKm(...)`.

```java
    public static double distanciaKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return redondear(RADIO_TIERRA_KM * c);
    }
```
- `distanciaKm(...)`: recibe dos puntos (latitud y longitud en grados) y devuelve los km entre ellos.
- `dLat` y `dLon`: cuánto cambian la latitud y la longitud entre los dos puntos. `Math.toRadians` pasa de grados a radianes, porque las funciones `sin` y `cos` de Java trabajan en radianes.
- `double a = ...`: la parte central de la fórmula de Haversine. Explicado fácil: combina la diferencia norte-sur (`dLat`) con la diferencia este-oeste (`dLon`), corrigiendo que los meridianos se juntan hacia los polos (por eso el `cos` de las latitudes).
- `double c = 2 * Math.atan2(...)`: convierte ese valor en el **ángulo** que hay entre los dos puntos, visto desde el centro de la Tierra.
- `RADIO_TIERRA_KM * c`: ángulo por radio = **largo del arco** sobre la superficie. Esos son los km.
- `redondear(...)`: lo deja con 2 decimales.

```java
    public static int minutosEstimados(double km) {
        return (int) Math.ceil(km / VELOCIDAD_PROMEDIO_KMH * 60);
    }
```
- Tiempo = distancia ÷ velocidad. `km / 30` da horas; `* 60` lo pasa a minutos.
- `Math.ceil` redondea **para arriba** (2.1 → 3), mejor sobrestimar que llegar tarde.
- `(int)` lo convierte a número entero.

```java
    public static Ruta ruta(double lat1, double lon1, double lat2, double lon2) {
        double km = distanciaKm(lat1, lon1, lat2, lon2);
        return new Ruta(km, minutosEstimados(km), Ruta.Fuente.HAVERSINE);
    }
```
- Arma una `Ruta` completa usando solo Haversine. Es lo que usa el plan B de OSRM.

```java
    public static double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
```
- Truco para redondear a 2 decimales: 12.3456 × 100 = 1234.56 → `round` → 1235 → ÷ 100 = 12.35.

---

## `OsrmGeoService.java`: el que pregunta al mapa

```java
public class OsrmGeoService implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(OsrmGeoService.class);

    private final RestClient restClient;
    private final boolean habilitado;

    public OsrmGeoService(RestClient restClient, boolean habilitado) {
        this.restClient = restClient;
        this.habilitado = habilitado;
    }
```
- `implements GeoService`: "cumplo el contrato de `GeoService`". Por eso tiene que tener el método `calcularRuta`.
- `Logger log = LoggerFactory.getLogger(...)`: el **logger** es el "diario" donde la clase anota lo que pasa. Usamos SLF4J, como piden los lineamientos (§9). Uno por clase.
- `private final RestClient restClient`: la herramienta de Spring para hacer pedidos HTTP. `final` = se asigna una sola vez, en el constructor.
- `private final boolean habilitado`: un interruptor para apagar OSRM desde la configuración (`app.geo.enabled=false`).
- El **constructor** recibe las dos cosas y las guarda (`this.restClient = restClient`). Esto es **inyección por constructor**: la clase no fabrica sus herramientas, se las dan.

```java
    @Override
    public Ruta calcularRuta(double latOrigen, double lonOrigen, double latDestino, double lonDestino) {
        if (!habilitado) {
            log.debug("OSRM deshabilitado por configuración, se usa Haversine");
            return Haversine.ruta(latOrigen, lonOrigen, latDestino, lonDestino);
        }
```
- `@Override`: "este método es el del contrato". Si lo escribiéramos mal, el compilador avisaría.
- Si OSRM está apagado, usa Haversine directamente. `log.debug` anota a nivel DEBUG (solo para diagnóstico).

```java
        String coordenadas = String.format(Locale.US, "%f,%f;%f,%f", lonOrigen, latOrigen, lonDestino, latDestino);
```
- OSRM pide las coordenadas en un formato especial: `longitud,latitud;longitud,latitud`. **Ojo: primero va la longitud.**
- `Locale.US` hace que los decimales salgan con **punto** (`-64.188`). Con el idioma español de la computadora saldrían con coma (`-64,188`) y OSRM no lo entendería.

```java
        try {
            OsrmRespuesta respuesta = restClient.get()
                    .uri("/route/v1/driving/{coordenadas}?overview=false", coordenadas)
                    .retrieve()
                    .body(OsrmRespuesta.class);
```
- `try { ... } catch`: "intentá esto, y si explota, atajalo abajo".
- `restClient.get()`: arma un pedido **GET**.
- `.uri("/route/v1/driving/{coordenadas}?overview=false", coordenadas)`: la dirección. `{coordenadas}` se reemplaza por el valor. `driving` = en auto; `overview=false` = "no me mandes el dibujo de la ruta, solo los números".
- `.retrieve()`: lo envía y espera la respuesta.
- `.body(OsrmRespuesta.class)`: convierte el JSON de la respuesta en un objeto `OsrmRespuesta` (lo hace Jackson).

```java
            if (respuesta == null || !"Ok".equals(respuesta.code()) || respuesta.routes() == null
                    || respuesta.routes().isEmpty()) {
                log.warn("OSRM respondió sin rutas válidas (code={}), se usa Haversine",
                        respuesta == null ? null : respuesta.code());
                return Haversine.ruta(latOrigen, lonOrigen, latDestino, lonDestino);
            }
```
- Si OSRM contestó pero sin una ruta útil (por ejemplo `code = "NoRoute"`), anota un **WARN** (algo raro, pero no grave) y usa el plan B.
- `{}` en el mensaje del log es un **lugar para un valor**: SLF4J lo reemplaza por el parámetro. Los lineamientos piden usar `{}` y no concatenar con `+`.

```java
            OsrmRuta ruta = respuesta.routes().getFirst();
            double km = Haversine.redondear(ruta.distance() / 1000.0);
            int minutos = (int) Math.ceil(ruta.duration() / 60.0);
            log.info("Ruta OSRM calculada: {} km, {} min", km, minutos);
            return new Ruta(km, minutos, Ruta.Fuente.OSRM);
```
- `getFirst()`: toma la primera ruta (la mejor).
- OSRM devuelve la distancia en **metros** (÷ 1000 → km) y la duración en **segundos** (÷ 60 → minutos).
- Devuelve una `Ruta` marcada como `OSRM`.

```java
        } catch (RestClientException e) {
            log.warn("OSRM no disponible ({}), se usa Haversine como respaldo", e.getMessage());
            return Haversine.ruta(latOrigen, lonOrigen, latDestino, lonDestino);
        }
    }
```
- `RestClientException`: cualquier problema con el pedido: timeout, sin internet, error 500, etc.
- En vez de romper todo, **usa el plan B**. Así una falla externa nunca frena el negocio.

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRespuesta(String code, List<OsrmRuta> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRuta(double distance, double duration) {
    }
}
```
- Dos records **internos** que representan la parte del JSON de OSRM que nos interesa:
  ```json
  { "code": "Ok", "routes": [ { "distance": 1220.5, "duration": 180.3, ... } ], ... }
  ```
- `@JsonIgnoreProperties(ignoreUnknown = true)`: "si el JSON trae más campos de los que conozco, ignoralos". OSRM manda muchos más datos y no queremos que Jackson se queje.

---

## `GeoAutoConfiguration.java`: el enchufe automático

```java
@AutoConfiguration
public class GeoAutoConfiguration {
```
- `@AutoConfiguration`: una configuración que Spring Boot carga **sola** cuando encuentra la librería en el proyecto. El microservicio no tiene que escribir nada para usarla.
- **¿Por qué hace falta?** Spring solo busca clases dentro del paquete de la aplicación (`utnfc.isi.back.tpi.solicitudes`), y esta librería está en otro paquete (`utnfc.isi.back.tpi.geo`). La auto-configuración es la forma oficial de "presentarse".

```java
    @Bean
    @ConditionalOnMissingBean
    public GeoService geoService(
            @Value("${app.geo.base-url:https://router.project-osrm.org}") String baseUrl,
            @Value("${app.geo.enabled:true}") boolean habilitado,
            @Value("${app.geo.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.geo.read-timeout-ms:3000}") int readTimeoutMs) {
```
- `@Bean`: "Spring, el objeto que devuelve este método guardalo y prestáselo a quien pida un `GeoService`".
- `@ConditionalOnMissingBean`: "creá este bean **solo si** nadie más creó un `GeoService`". Así un microservicio podría reemplazarlo por otro (por ejemplo, uno falso para pruebas).
- Cada `@Value("${clave:valorPorDefecto}")` lee la configuración del `application.yml` del microservicio. Si no está, usa el valor de después de los `:`:
  - `base-url`: la dirección del servidor público de OSRM;
  - `enabled`: el interruptor;
  - `connect-timeout-ms` = 2000: hasta 2 segundos para **conectarse**;
  - `read-timeout-ms` = 3000: hasta 3 segundos para **recibir la respuesta**.

```java
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
```
- La **fábrica de conexiones** HTTP, con los timeouts. Sin timeouts, si OSRM no contesta, el pedido podría quedar colgado para siempre.

```java
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        return new OsrmGeoService(restClient, habilitado);
    }
}
```
- Arma el `RestClient` con la URL base (los pedidos después solo dicen `/route/v1/...`) y la fábrica con timeouts.
- Crea el `OsrmGeoService` y lo devuelve. Desde ese momento, cualquier clase que pida `GeoService` en su constructor recibe este objeto.

---

## El archivo `AutoConfiguration.imports`

```
utnfc.isi.back.tpi.geo.GeoAutoConfiguration
```
- Está en `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Es la "lista de invitados": al arrancar, Spring Boot lee este archivo dentro de cada `.jar` y carga las clases que figuran. Sin esta línea, `GeoAutoConfiguration` nunca se ejecutaría.

---

## ¿Quién usa qué?

| Microservicio | Usa | Dónde |
|---|---|---|
| ms-tecnicos | `Haversine.distanciaKm(...)` | `TecnicoService.buscarCandidatos`: filtra a menos de 13 km. Es local y rápido, así no satura a OSRM con un pedido por técnico |
| ms-solicitudes | `Haversine.minutosEstimados(...)` | `SolicitudService.elegir`: estima minutos para la simulación |
| ms-solicitudes | `GeoService.calcularRuta(...)` | `SolicitudService.aceptar`: **un solo** pedido a OSRM, para el técnico que aceptó (el "finalista"), y con eso se calcula el costo real |
