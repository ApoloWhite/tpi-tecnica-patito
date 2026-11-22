# 09 – La colección de Postman por dentro

Cómo **usar** la colección está en la [guía paso a paso](../GUIA-PASO-A-PASO.md) (sección 6). Acá se explica **cómo está armada**, para poder modificarla o agregar pruebas.

## Los archivos

| Archivo | Qué es |
|---|---|
| `postman/Tecnica-Patito.postman_collection.json` | La colección: carpetas, peticiones, cuerpos y scripts de prueba |
| `postman/Tecnica-Patito.postman_environment.json` | El entorno "Técnica Patito – Local": las URLs |

Los dos son JSON en el formato **Postman Collection v2.1**, que también entiende **Newman** (Postman por línea de comandos).

---

## Las variables: los "casilleros"

Una variable se escribe `{{nombre}}` y Postman la reemplaza por su valor antes de enviar. Hay dos lugares donde se guardan:

| Dónde | Variables | Para qué |
|---|---|---|
| **Entorno** (environment) | `gateway_url`, `keycloak_url`, `client_id`, `ms_*_url` | Las direcciones. Si cambia el puerto, se cambia en un solo lugar |
| **Colección** (collection variables) | `token_admin`, `token_cliente`, `token_tecnico`, `clienteId`, `tecnicoId`, `especialidadId`, `solicitudId`, `fechaHabil`, `fechaFueraHorario`… | Lo que se va llenando **mientras corre** la colección |

Si una variable existe en los dos lugares, **gana el entorno**.

---

## Una petición por dentro

Ejemplo: **"5. EP-03 Técnico acepta → ASIGNADA con costo"**.

```
POST {{gateway_url}}/api/v1/solicitudes/{{solicitudId}}/aceptar/{{tecnicoId}}
Authorization: Bearer {{token_tecnico}}
```
- **URL**: arma la ruta con variables. `{{solicitudId}}` lo guardó el paso 2 y `{{tecnicoId}}` el paso 3.
- **Pestaña Authorization**: tipo *Bearer Token*, valor `{{token_tecnico}}`. Cada petición usa el token del rol que corresponde en la vida real.

**Pestaña Tests** (JavaScript que corre **después** de recibir la respuesta):

```javascript
pm.test("Status 200", () => pm.response.to.have.status(200));
pm.test("Estado ASIGNADA", () => pm.expect(pm.response.json().estado).to.eql("ASIGNADA"));
pm.test("Costo calculado", () => { const b = pm.response.json(); ["distanciaKm", "tiempoEstimadoMin", "estrategiaCosto", "costoBase", "recargo", "total"].forEach(k => pm.expect(b[k], k).to.not.be.null); });
```
- `pm` es el objeto de Postman con todo lo que necesitás.
- `pm.test("nombre", () => { ... })`: define una **prueba** con nombre. Si lo de adentro falla, la prueba sale en rojo.
- `pm.response.to.have.status(200)`: "el código tiene que ser 200".
- `pm.response.json()`: el cuerpo de la respuesta como objeto.
- `pm.expect(valor).to.eql(esperado)`: "el valor tiene que ser igual a…". Usa la librería **Chai** (`to.eql`, `to.be.above`, `to.include`, `to.not.be.null`…).
- El tercer test recorre la lista de campos y verifica que ninguno sea `null`.

---

## Cómo pasa un dato de una petición a la siguiente

En **"2. EP-01 Crear solicitud"**, el test hace:

```javascript
pm.collectionVariables.set("solicitudId", pm.response.json().id);
```
- Guarda el `id` de la solicitud recién creada en la variable `solicitudId`. Todas las peticiones siguientes usan `{{solicitudId}}` en su URL.

En **"Token CLIENTE (cliente1)"**:

```javascript
const token = pm.response.json().access_token;
pm.test("Devuelve access_token", () => pm.expect(token).to.be.a("string"));
pm.collectionVariables.set("token_cliente", token);
```
- Pide el token a Keycloak (`POST {{keycloak_url}}` con `grant_type=password`, `client_id`, `username` y `password` en formato *x-www-form-urlencoded*) y lo guarda.

---

## Los scripts "antes de enviar" (Pre-request)

### El de la colección: fechas dinámicas

```javascript
function proximoHabil(hora) {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
    const p = n => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(hora)}:00:00`;
}
pm.collectionVariables.set("fechaHabil", proximoHabil(10));
pm.collectionVariables.set("fechaFueraHorario", proximoHabil(21));
```
- Corre **antes de cada petición** de la colección.
- Calcula el **próximo día hábil**: empieza por mañana y, mientras sea domingo (`0`) o sábado (`6`), suma un día.
- `fechaHabil` = ese día a las **10:00** (horario normal → estrategia ESTANDAR).
- `fechaFueraHorario` = ese día a las **21:00** (fuera de horario → recargo).
- **¿Por qué no una fecha fija?** Una fecha escrita a mano quedaría en el pasado o caería en fin de semana, y la estrategia de costo cambiaría. Así el resultado es siempre el mismo.
- `getMonth() + 1`: en JavaScript los meses van de 0 a 11.

### En algunas peticiones: datos al azar

```javascript
pm.collectionVariables.set("dniCliente", String(_.random(40000000, 49999999)));
```
- Antes de **"Registrar cliente"**, inventa un DNI al azar (`_` es la librería **Lodash**, que viene incluida en Postman). Como la base H2 guarda los datos entre corridas, un DNI fijo daría 422 la segunda vez.

```javascript
pm.collectionVariables.set("legajoTecnico", "T-" + Date.now().toString().slice(-6));
```
- Un legajo único a partir de la hora actual en milisegundos (los últimos 6 dígitos).

---

## Los cuerpos con variables numéricas

```json
{
  "clienteId": {{clienteId}},
  "tiposServicio": ["REPARACION"],
  "especialidadId": {{especialidadId}},
  "fechaHoraProgramada": "{{fechaHabil}}",
  "modoAsignacion": "MANUAL"
}
```
- `{{clienteId}}` va **sin comillas** porque es un número; `"{{fechaHabil}}"` va **con comillas** porque es texto. Postman reemplaza el texto tal cual, así que el JSON final queda bien formado.
- El editor de Postman marca `{{clienteId}}` en rojo ("JSON inválido") porque todavía no hizo el reemplazo. Es normal.

---

## Las carpetas y su orden

| Carpeta | Depende de | Deja algo para después |
|---|---|---|
| 00 – Seguridad | nada | los 3 tokens |
| 01 – Clientes | tokens | crea y **borra** un cliente (queda limpio) |
| 02 – Técnicos | tokens | crea un técnico **no disponible** (no afecta ninguna oferta) |
| 03 – Solicitudes | tokens, `fechaHabil` | las solicitudes de prueba se **cancelan** al final |
| 04 – Flujo principal (manual) | tokens | termina en FINALIZADA |
| 04B – Flujo principal (automático) | tokens | termina en FINALIZADA o CANCELADA |
| 05 – Escenarios negativos | tokens | EN-06 arma un escenario y lo **limpia** al final |
| 06 – Escenarios manuales | tokens + una acción tuya | se cancelan al final |

**Regla de oro de esta colección:** cada escenario deja al técnico 1 (Juan) y a la técnica 2 (Ana) **libres** cuando termina. Si no, la segunda corrida chocaría con RN-5 (máximo 2 solicitudes en espera). Por eso existen los pasos "Limpieza: …".

---

## Correrla por línea de comandos (Newman)

```bash
npx newman run postman/Tecnica-Patito.postman_collection.json \
  -e postman/Tecnica-Patito.postman_environment.json \
  --folder "00 – Seguridad" --folder "01 – Clientes" --folder "02 – Técnicos" \
  --folder "03 – Solicitudes" --folder "04 – Flujo principal (modo manual)" \
  --folder "04B – Flujo principal (modo automático)" --folder "05 – Escenarios negativos"
```
- `npx newman run`: baja Newman (si no lo tenés) y corre la colección.
- `-e`: el archivo de entorno.
- `--folder`: qué carpetas correr. Se deja afuera la 06 porque necesita que hagas algo a mano.
- `--env-var gateway_url=http://localhost:18080`: para cambiar una variable sin editar el archivo.
- Al final muestra una tabla con cuántas peticiones y cuántos tests pasaron. Tienen que dar **0 failed**.
