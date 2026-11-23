# Documentación de Técnica Patito

Leela en este orden. Cada parte usa lo que explicó la anterior.

| # | Documento | Qué vas a aprender | Para quién |
|---|---|---|---|
| 0 | [ACCESOS.md](ACCESOS.md) | **Todos los enlaces y credenciales**: Keycloak (usuarios, consola, tokens), consolas H2 de cada microservicio, Swagger, frontend y Actuator | Tenelo a mano |
| 1 | [GUIA-PASO-A-PASO.md](GUIA-PASO-A-PASO.md) | Qué es cada pieza (con la analogía del edificio), cómo encender todo y cómo mostrar el flujo con el frontend, Swagger y Postman | Todos. Empezá acá |
| 2 | [COMO-FUNCIONA.md](COMO-FUNCIONA.md) | El viaje completo de una petición por adentro del sistema, los **patrones de diseño** que se usan (Strategy, State, Circuit Breaker…) y un diccionario de todas las palabras raras (bean, DTO, JPA, JWT…) | Antes de leer el código |
| 2b | [TECNOLOGIAS.md](TECNOLOGIAS.md) | Todas las tecnologías y frameworks usados, con su versión real, para qué sirve cada uno y por qué se eligió | Todos |
| 3 | [codigo/01-proyecto-maven.md](codigo/01-proyecto-maven.md) | Cómo está armado el proyecto: el `pom.xml` padre y los de cada módulo | Código |
| 4 | [codigo/02-commons-geo.md](codigo/02-commons-geo.md) | La librería que calcula distancias (Haversine y OSRM) | Código |
| 5 | [codigo/03-ms-clientes.md](codigo/03-ms-clientes.md) | **El microservicio más simple, explicado entero.** Acá se explican las piezas que se repiten en todos: seguridad, errores, correlation id y Swagger | Código. Leelo antes que los otros microservicios |
| 6 | [codigo/04-ms-tecnicos.md](codigo/04-ms-tecnicos.md) | Técnicos, certificaciones, zonas y búsqueda de candidatos | Código |
| 7 | [codigo/05-ms-solicitudes.md](codigo/05-ms-solicitudes.md) | El cerebro: estados, ofertas automáticas, scheduler, costos (Strategy) y llamadas a otros servicios | Código |
| 8 | [codigo/06-api-gateway.md](codigo/06-api-gateway.md) | La puerta de entrada: rutas, validación del token y Swagger unificado | Código |
| 9 | [codigo/07-docker-y-keycloak.md](codigo/07-docker-y-keycloak.md) | Dockerfiles, docker-compose y el realm de Keycloak | Infraestructura |
| 10 | [codigo/08-frontend.md](codigo/08-frontend.md) | La página web: login con Keycloak y cómo llama al backend | Código |
| 11 | [codigo/09-postman.md](codigo/09-postman.md) | Cómo está armada la colección de Postman y qué hacen sus scripts | Pruebas |
| 🎤 | [presentacion.html](presentacion.html) | Presentación de 20 diapositivas: dominio y reglas, arquitectura, tecnologías, patrones Strategy / Snapshot / Adapter (con código y ruta) y ciclo de vida. Abrila en el navegador: flechas para avanzar, `F` pantalla completa, `Ctrl+P` para PDF | Defensa |

> 💡 **Cómo leer los documentos de código:** cada archivo aparece en pedacitos. Primero va el código como está en el proyecto y después una lista que explica cada línea. Donde ves `...` se omitió algo que se repite (por ejemplo, un campo igual al anterior o un texto largo de Swagger); abrí el archivo real para verlo completo. Los `import` se explican agrupados, porque solo dicen "voy a usar tal clase de tal paquete".
>
> Los `.java` también tienen comentarios cortos (`/** ... */`) que explican el **porqué** de cada clase. Acá está el detalle línea por línea, para no llenar el código de comentarios y que siga siendo fácil de leer y de cambiar.
