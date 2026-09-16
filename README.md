# mto-users

Servicio de administración de **usuarios, roles y perfiles** del dominio `MTO`, construido con
Spring Boot 4 y Java 25 sobre la **Admin API de Keycloak**. Keycloak es la única fuente de verdad:
`mto-users` no tiene base de datos ni broker, no persiste nada y no emite tokens. Lo que hace es
exponer, con el mismo contrato de seguridad y de errores que el resto de servicios, lo que hasta
ahora solo se podía hacer desde la consola de Keycloak:

- **usuarios**: buscar con filtros y paginación, crear, modificar datos básicos, habilitar y
  deshabilitar, borrar, fijar una contraseña temporal y enviar acciones requeridas por correo
  (`UPDATE_PASSWORD`, `VERIFY_EMAIL`...);
- **roles**: listar los clientes del realm y sus roles, ver los roles de un usuario, asignar y
  quitar roles de cliente;
- **perfiles**: listar los perfiles de la plataforma, ver qué concede cada uno, y asignarlos y
  quitarlos a un usuario.

Es un servicio hermano de `mto-configuration`, `mto-stock`, `mto-maintenance` y `mto-gateway`, con
la misma estructura y las mismas convenciones. Se publica detrás del gateway en `/api/users/**`.

---

## Decisiones de diseño

### Perfiles = roles compuestos de realm

La plataforma ya define sus perfiles como **roles compuestos de realm**: `mto-viewer`, `mto-admin`,
`mto-warehouse-admin`, `mto-maintenance-manager`, `mto-ops`... Cada servicio declara los suyos en su
`keycloak/<servicio>-partial-import.json` y Keycloak los expande en `resource_access` al emitir el
token. `mto-users` no inventa una segunda definición: un **perfil es un rol de realm cuyo nombre
empieza por `mto-`** (`app.profiles.prefix`), y

- **asignar un perfil** es asignar ese rol de realm al usuario (`POST .../role-mappings/realm`);
- **quitar un perfil** es quitar ese rol, que quita exactamente lo que dio: no toca los roles de
  cliente asignados directamente ni los que aporten otros perfiles, así que no hay aritmética de
  solapamientos;
- **los perfiles de un usuario** son sus roles de realm con el prefijo.

Los perfiles son, por tanto, **persistidos en Keycloak y calculados en ninguna parte**. Frente a
las alternativas: definirlos en YAML duplicaría lo que ya vive en los partial imports y obligaría a
calcular qué roles quitar cuando dos perfiles se solapan; los grupos de Keycloak añadirían un
concepto nuevo al realm para modelar lo que los compuestos ya modelan. `mto-users` aporta tres
perfiles propios: `mto-users-viewer`, `mto-users-manager` y `mto-users-admin`.

### Keycloak Admin Client, detrás de un puerto propio

Se usa el cliente oficial (`org.keycloak:keycloak-admin-client` 26.0.x, con versión desacoplada del
servidor y compatible con el Keycloak 26.1 de `mto-platform`): la superficie que hace falta viene
tipada (`RealmResource`, `UsersResource`, `RolesResource`...) y la biblioteca gestiona sola el token
de la cuenta de servicio, renovación incluida. A cambio arrastra RESTEasy y Jackson 2 para su uso
interno; Spring Boot 4 gestiona Jackson 2 junto a Jackson 3, y la API se serializa con Jackson 3
(`MtoUsersApplicationTests` lo comprueba).

Ninguna clase de la biblioteca sale de `keycloak/` y `mapper/`: los servicios hablan con la interfaz
`KeycloakAdminGateway`, cuya única implementación traduce las excepciones JAX-RS del cliente a las
de negocio. Los timeouts se fijan construyendo el cliente HTTP con `ResteasyClientBuilderImpl`
(`app.keycloak.connect-timeout`, `read-timeout`, `pool-size`). Crear el bean no toca la red: el
token se pide en la primera llamada, así que la aplicación arranca aunque Keycloak no esté listo.

### Cuenta de servicio con permisos mínimos

`mto-users` administra el realm con el cliente confidencial **`mto-users-svc`** (`client_credentials`)
del **propio realm `mto`**, nunca con una credencial del realm `master`. Su cuenta de servicio
necesita estos roles del cliente `realm-management`:

| Rol | Para qué |
|---|---|
| `view-users`, `query-users` | Buscar, contar y leer usuarios, sus role-mappings, sus sesiones, sus consentimientos, sus credenciales y quién tiene un rol |
| `manage-users` | Crear, modificar, habilitar, borrar, fijar contraseñas, enviar acciones por correo, cerrar sesiones (normales y offline), quitar credenciales y **cambiar los role-mappings** de un usuario (realm y cliente) |
| `view-clients`, `query-clients` | Listar clientes y los roles de cada uno, resolver `clientId` → UUID |
| `view-realm` | Leer los roles de realm (los perfiles) y sus compuestos |

`manage-users` permite en Keycloak mapear **cualquier** rol, `realm-management/realm-admin` incluido.
Por eso el servicio se niega a listar y a asignar roles de los clientes de
`app.keycloak.protected-clients` (`realm-management`, `broker`, `account`, `account-console`,
`admin-cli`, `security-admin-console`): sin esa lista, `users-roles-write` bastaría para hacerse
administrador del realm.

Una importación parcial no asigna roles a cuentas de servicio; en local los concede
`mto-platform/keycloak/apply-partials.sh` por la Admin API, y en un entorno desplegado se hace en la
consola (Clients → `mto-users-svc` → Service accounts roles). Ver [`keycloak/README.md`](keycloak/README.md).

### Errores como `ProblemDetail`

Todo error sale como `application/problem+json` (RFC 9457), el mismo formato que devuelve el gateway,
con `errorCode` (estable), `correlationId`, `timestamp` y, en los 400 de validación, `validationErrors`.

| Situación | Estado | `errorCode` |
|---|---|---|
| Validación de la petición | 400 | `REQ-VALIDATION`, `REQ-400` |
| Filtros que Keycloak no aplicaría como se pide (`search` + `attribute`, una clave de atributo repetida) | 400 | `SEARCH-400` |
| Keycloak rechaza la petición (política de contraseñas, atributo obligatorio...) | 400 | `KC-400` |
| Roles de un cliente protegido | 400 | `ROL-PROTECTED-CLIENT` |
| Sin token / sin permiso | 401 / 403 | `AUTH-401` / `AUTH-403` |
| Usuario, cliente, rol o perfil inexistente | 404 | `USR-404`, `CLI-404`, `ROL-404`, `PRF-404` |
| Sesión inexistente, ya cerrada o de otro usuario | 404 | `SES-404` |
| Credencial inexistente o de otro usuario | 404 | `CRED-404` |
| Usuario duplicado (username o email) | 409 | `USR-409` |
| Keycloak rechaza la cuenta de servicio (secreto o roles de `realm-management`) | 502 | `KC-ACCESS` |
| Keycloak responde con un error inesperado (500 sin SMTP, por ejemplo) | 502 | `KC-502` |
| Keycloak no responde (conexión, timeout); lleva `Retry-After` | 503 | `KC-503` |
| Cualquier otra cosa, sin detalles | 500 | `APP-500` |

### Auditoría y correlación

Cada operación que modifica algo deja una línea en el logger `com.alejandro.mtousers.audit`:
`action=USER_CREATED actor=usuarios.responsable actorId=<sub> targetUserId=<id> detail=...`. Cerrar
sesiones también deja la suya (`SESSION_REVOKED`, `ALL_SESSIONS_REVOKED`,
`OFFLINE_SESSION_REVOKED`, `ALL_OFFLINE_SESSIONS_REVOKED`) y quitar una credencial deja
`CREDENTIAL_DELETED` **con el tipo** —quitar un OTP no es lo mismo que quitar una contraseña
vieja—; las consultas no. El
detalle son nombres de roles, perfiles o acciones; **nunca una contraseña ni un token** (los DTOs
que llevan contraseña la ocultan en `toString()`). El identificador de correlación viaja en
`X-Correlation-Id` —lo genera el gateway o, si falta, este servicio—, va en cada línea de log a
través del MDC y vuelve en la respuesta y en cada `ProblemDetail`.

---

## Seguridad

Resource server de Keycloak, igual que los demás: valida firma, emisor, vigencia y —activada por
defecto— audiencia (`aud ⊇ {mto-users-api}`). Los roles de cliente de `mto-users-api` llegan como
`ROLE_<ROL>` (y `ROLE_CLIENT_<ROL>`); los de realm solo como `ROLE_REALM_<ROL>`, para que un rol de
realm llamado como un permiso nunca lo conceda.

| Operación | Permiso (`mto-users-api`) |
|---|---|
| `GET`/`HEAD /api/v1/users/**` (usuarios, roles, perfiles, catálogos) | `users-read` |
| `POST /api/v1/users`, `PUT /{id}`, `PATCH /{id}/enabled`, `POST /{id}/execute-actions-email` | `users-write` |
| `DELETE /api/v1/users/{id}` | `users-delete` |
| `PUT`/`DELETE /{id}/roles/clients/{clientId}` | `users-roles-write` |
| `POST /{id}/reset-password` | `users-password-reset` |
| `PUT`/`DELETE /{id}/profiles/{profileName}` | `users-profiles-write` |
| `DELETE /{id}/sessions`, `DELETE /{id}/sessions/{sessionId}` | `users-sessions-write` |
| `DELETE /{id}/offline-sessions`, `DELETE /{id}/offline-sessions/{sessionId}` | `users-sessions-write` |
| `DELETE /{id}/credentials/{credentialId}` | `users-credentials-write` |
| `/actuator/health`, `/actuator/info` | abiertos |
| resto de `/actuator/**` (lectura) | `ops-metrics` |
| `POST`/`DELETE /actuator/**` | `ops-write` |

Ningún permiso implica otro: `ApiAuthorizationRulesTest` lo comprueba verbo a verbo. Los perfiles
de la plataforma que los agrupan: `mto-users-viewer` (`users-read`), `mto-users-manager` (todo menos
borrar) y `mto-users-admin` (todo). `mto-ops` añade `users-read`, `ops-metrics` y `ops-write`.

Cerrar sesiones tiene permiso propio, `users-sessions-write`, y no lo dan ni `users-write` ni
`users-delete`: echar a todo el mundo de la aplicación no se parece a editar una ficha, y quien
administra datos no tiene por qué poder hacerlo. Las sesiones cuelgan de dos segmentos
(`/{id}/sessions/{sessionId}`), así que la regla de `DELETE` del usuario —que cubre uno— no las
alcanzaba: llevan sus dos `requestMatchers` explícitos, por delante de ella. Las offline son
sesiones igual y las cubre el mismo permiso.

Quitar una credencial tiene el suyo, `users-credentials-write`, separado de `users-password-reset`:
fijar una contraseña temporal deja entrar a su dueño con lo que el administrador ha puesto, pero
quitar una credencial puede ser quitarle el **segundo factor**, que es precisamente lo que protege
la cuenta. Ninguno de los dos implica al otro.

---

## API

Raíz interna `/api/v1/users`; pública, a través del gateway, `/api/users`. OpenAPI en
`/v3/api-docs` y Swagger UI en `/swagger-ui.html` (abiertos con `APP_SECURITY_EXPOSE_API_DOCS=true`,
que es el valor del perfil `dev`).

### Usuarios

| Método y ruta | Qué hace |
|---|---|
| `GET /api/v1/users?search&username&email&enabled&emailVerified&attribute&first=0&max=20` | Búsqueda paginada por desplazamiento (`max` ≤ 200). `search` mira username, email, nombre y apellidos; `enabled` y `emailVerified` se combinan con cualquiera; `attribute` se repite para filtrar por atributos (`?attribute=departamento:taller`). Respuesta: `{content, first, max, total}` |
| `POST /api/v1/users` | Alta. `201` + `Location`. Opcionales `temporaryPassword` (Keycloak obliga a cambiarla al entrar) y `requiredActions`. Habilitado salvo `enabled=false` |
| `GET /api/v1/users/{userId}` | Detalle |
| `PUT /api/v1/users/{userId}` | Datos básicos (`firstName`, `lastName`, `email`, `emailVerified`, `attributes`). Lo que no viene se conserva |
| `PATCH /api/v1/users/{userId}/enabled` | `{"enabled": true\|false}` |
| `DELETE /api/v1/users/{userId}` | `204` |
| `POST /api/v1/users/{userId}/reset-password` | `{"password", "temporary"}` (temporal por defecto). `204` |
| `POST /api/v1/users/{userId}/execute-actions-email` | `{"actions": [...], "lifespanSeconds"?, "clientId"?, "redirectUri"?}`. `202`. Necesita SMTP en el realm |
| `GET /api/v1/users/{userId}/sessions` | Sesiones abiertas: `[{id, username, ipAddress, startedAt, lastAccessAt, clients}]` |
| `DELETE /api/v1/users/{userId}/sessions` | Cierra todas. `204`, idempotente |
| `DELETE /api/v1/users/{userId}/sessions/{sessionId}` | Cierra una. `204`; `404 SES-404` si esa sesión no es de ese usuario |
| `GET /api/v1/users/{userId}/offline-sessions` | Sesiones offline (tokens con `offline_access`), mismo cuerpo que las normales |
| `DELETE /api/v1/users/{userId}/offline-sessions` | Cierra todas las offline. `204`, idempotente |
| `DELETE /api/v1/users/{userId}/offline-sessions/{sessionId}` | Cierra una offline. `204`; `404 SES-404` si no es de ese usuario |
| `GET /api/v1/users/{userId}/credentials` | `[{id, type, userLabel, createdAt}]`. Sin secretos y sin metadatos de cifrado |
| `DELETE /api/v1/users/{userId}/credentials/{credentialId}` | Quita una credencial. `204`; `404 CRED-404` si no es de ese usuario |

### Roles

| Método y ruta | Qué hace |
|---|---|
| `GET /api/v1/users/roles/clients` | Clientes cuyos roles se pueden asignar (los protegidos no aparecen) |
| `GET /api/v1/users/roles/clients/{clientId}` | Roles de un cliente |
| `GET /api/v1/users/roles/clients/{clientId}/{roleName}/users?first=0&max=20` | Quién tiene ese rol de cliente, **asignado directamente** |
| `GET /api/v1/users/{userId}/roles` | Roles asignados **directamente**: `{realmRoles, clientRoles: [{clientId, roles}]}` |
| `PUT /api/v1/users/{userId}/roles/clients/{clientId}` | `{"roles": [...]}`. Añade; devuelve las asignaciones actualizadas |
| `DELETE /api/v1/users/{userId}/roles/clients/{clientId}` | `{"roles": [...]}` en el cuerpo. Quita; devuelve las asignaciones actualizadas |

### Perfiles

| Método y ruta | Qué hace |
|---|---|
| `GET /api/v1/users/profiles` | Perfiles de la plataforma (roles de realm `mto-*`) |
| `GET /api/v1/users/profiles/{profileName}` | Qué concede: `{name, description, clientRoles: [{clientId, roles}], realmRoles}` |
| `GET /api/v1/users/profiles/{profileName}/users?first=0&max=20` | Quién tiene ese perfil |
| `GET /api/v1/users/{userId}/profiles` | Perfiles del usuario |
| `PUT /api/v1/users/{userId}/profiles/{profileName}` | Asigna (idempotente); devuelve los perfiles del usuario |
| `DELETE /api/v1/users/{userId}/profiles/{profileName}` | Quita; devuelve los perfiles del usuario |

`roles` y `profiles` son segmentos literales y Spring MVC los resuelve antes que `{userId}`; los
ids de usuario se validan (`[A-Za-z0-9:._-]{1,255}`) antes de llamar a Keycloak.

### Atributos y el perfil de usuario del realm

Los `attributes` de un usuario solo se guardan si el **perfil de usuario declarativo** del realm
admite atributos no gestionados. Keycloak 26 los **descarta en silencio** por defecto: la llamada
responde 200 y el atributo no existe. El realm de `mto-platform` lo activa con
`unmanagedAttributePolicy: ADMIN_EDIT` (visibles y editables por administración, no por el propio
usuario), y el realm de `KeycloakUsersIT` lleva la misma configuración, que es lo que prueba que el
ida y vuelta funciona. En un realm que no la tenga, este campo no hace nada: se quita la política y
se quitan los atributos.

### Búsqueda por atributo

`?attribute=clave:valor`, repetible. Viaja al parámetro `q` de la Admin API, que compara el valor
exacto (no hay `contains` ni comodines) y combina con **Y** las claves distintas: `?attribute=
departamento:taller&attribute=turno:noche` devuelve a quien cumpla las dos. El `total` de la página
se cuenta con el mismo filtro, no sobre el realm entero.

Dos combinaciones se rechazan con `400 SEARCH-400` en vez de pasarlas al servidor, porque allí el
filtro desaparece sin dejar rastro y la respuesta parece correcta:

- **`search` + `attribute`**: con los dos presentes Keycloak aplica `search` y descarta `q` entero,
  de modo que la búsqueda devolvería usuarios que no tienen ese atributo. Para acotar además por
  identidad están `username` y `email`, que sí se combinan.
- **la misma clave repetida** (`?attribute=departamento:taller&attribute=departamento:obra`):
  Keycloak parsea `q` a un mapa, así que solo sobrevive el último par y el otro filtro se pierde.

Como el filtro es por atributos, hace falta que el realm los guarde: ver el apartado anterior.

### Búsqueda inversa: quién tiene un perfil o un rol

`GET /profiles/{profileName}/users` y `GET /roles/clients/{clientId}/{roleName}/users` responden la
pregunta contraria a la de siempre. Las dos devuelven una lista plana de usuarios —el mismo
`UserResponse` de la búsqueda— paginada con `first`/`max`.

Keycloak devuelve **solo asignaciones directas y no expande los compuestos**, y eso se nota justo
donde el modelo de perfiles lo usa: quien tiene `stock-read` porque le asignaron el perfil
`mto-warehouse-viewer` aparece en la lista del perfil, no en la del rol. La lista de un rol de
cliente dice quién lo tiene *suelto*; para «quién puede leer el almacén» hay que mirar los perfiles
que lo conceden. Los clientes protegidos tampoco se listan aquí (`400 ROLE-PROTECTED-CLIENT`).

### Sesiones

`GET /{userId}/sessions` lista lo que Keycloak tiene abierto ahora mismo para ese usuario, con la
hora de inicio, la del último acceso, la IP y los clientes por los que ha pasado (el `clientId`, no
el UUID interno). `DELETE /{userId}/sessions` las cierra todas y es idempotente; `DELETE
/{userId}/sessions/{sessionId}` cierra una.

Deshabilitar a alguien **no cierra lo que ya tenía abierto**: su token sigue siendo válido hasta que
caduque. Dar de baja de verdad son las dos cosas, `PATCH /enabled` y `DELETE /sessions`.

Cerrar una sesión suelta comprueba antes que sea de ese usuario y responde `404 SES-404` si no lo
es. No es una comprobación de cortesía: el endpoint de Keycloak que borra una sesión cuelga del
realm y no del usuario, así que sin ella un id ajeno —de otra persona— se cerraría igual.

### Sesiones offline: lo que no cierra «cerrar todas»

Un token emitido con el scope `offline_access` está hecho para sobrevivir al cierre de sesión, y lo
hace: **no abre sesión normal** (no sale en `GET /sessions`), **no lo cierra** `DELETE /sessions` y
sigue refrescándose después. Por eso tiene su propio recurso, con la misma forma que el anterior:

```
GET    /api/v1/users/{userId}/offline-sessions
DELETE /api/v1/users/{userId}/offline-sessions
DELETE /api/v1/users/{userId}/offline-sessions/{sessionId}
```

**Deshabilitar a alguien no revoca nada.** Mientras está deshabilitado, Keycloak bloquea el
refresco; al volver a habilitarlo, el mismo token offline vuelve a funcionar. Lo único que lo mata
es cerrar su sesión offline. Así que dar de baja de verdad son tres llamadas, y en este orden:

```
PATCH  /api/v1/users/{id}/enabled        {"enabled": false}
DELETE /api/v1/users/{id}/sessions
DELETE /api/v1/users/{id}/offline-sessions
```

Keycloak no ofrece «las sesiones offline de este usuario»: se consultan cliente a cliente. El
servicio no recorre todos los clientes del realm, sino que mira primero los **consentimientos** del
usuario, donde cada token offline deja una concesión `Offline Token` con el cliente al que
pertenece; con eso pregunta solo por esos. Si nadie usa `offline_access` en el realm, estas llamadas
responden una lista vacía y no cuestan nada.

### Credenciales

```
GET    /api/v1/users/{userId}/credentials
DELETE /api/v1/users/{userId}/credentials/{credentialId}
```

El caso para el que existe es un **segundo factor perdido**: quitar la credencial `otp` deja que la
persona vuelva a enrolar el suyo. `type` es el nombre de Keycloak para la clase de credencial
(`password`, `otp`, `webauthn`, `webauthn-passwordless`) y `userLabel` el nombre que le puso su
dueño al enrolarla.

La respuesta **no lleva secretos ni nada de cómo están guardados**. `secretData` —el hash y su
sal— Keycloak no lo devuelve nunca, pero sí devuelve `credentialData`, que para una contraseña trae
el algoritmo y sus parámetros (`argon2`, iteraciones, memoria); eso se queda fuera de la API, que
no es información que necesite quien administra usuarios.

Quitar la contraseña deja a esa persona sin poder entrar con contraseña hasta que se le fije otra
con `reset-password`; es una operación legítima y el servicio no la bloquea, pero conviene saberlo.
Un id de credencial que no sea de ese usuario es `404 CRED-404`, y la auditoría registra el **tipo**
de lo que se quitó.

### Ejemplos con curl

Con `mto-platform` levantado y el realm ensamblado con usuarios de desarrollo
(`usuarios.responsable` / `local` tiene el perfil `mto-users-admin`):

```bash
TOKEN=$(curl -s -X POST http://auth.mto.local:8082/realms/mto/protocol/openid-connect/token \
  -d grant_type=password -d client_id=mto-frontend \
  -d username=usuarios.responsable -d password=local | jq -r .access_token)

# Directo al servicio (8084) o a través del gateway (8090, prefijo /api/users)
BASE=http://localhost:8084/api/v1/users
# BASE=http://localhost:8090/api/users

# Buscar
curl -s -H "Authorization: Bearer $TOKEN" "$BASE?search=almacen&enabled=true&max=10" | jq

# Crear con contraseña temporal
USER_ID=$(curl -s -X POST "$BASE" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "X-Correlation-Id: alta-ana-001" \
  -d '{"username":"ana.nueva","firstName":"Ana","lastName":"Nueva","email":"ana.nueva@mto.local",
       "temporaryPassword":"Cambiame.123","requiredActions":["UPDATE_PASSWORD"]}' | jq -r .id)

# Modificar datos básicos (lo que no viene se conserva)
curl -s -X PUT "$BASE/$USER_ID" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"lastName":"Nueva Probada","attributes":{"departamento":["operaciones"]}}' | jq

# Deshabilitar
curl -s -X PATCH "$BASE/$USER_ID/enabled" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"enabled":false}' | jq .enabled

# Contraseña temporal
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/$USER_ID/reset-password" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"password":"Otra.Temporal.456","temporary":true}'

# Correo con acciones requeridas (sin SMTP en el realm responde 502 KC-502)
curl -s -X POST "$BASE/$USER_ID/execute-actions-email" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"actions":["UPDATE_PASSWORD","VERIFY_EMAIL"],"lifespanSeconds":3600}'

# Roles: catálogo, asignar y quitar
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/roles/clients" | jq
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/roles/clients/mto-stock-api" | jq
curl -s -X PUT "$BASE/$USER_ID/roles/clients/mto-stock-api" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"roles":["stock-read","stock-write"]}' | jq
curl -s -X DELETE "$BASE/$USER_ID/roles/clients/mto-stock-api" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"roles":["stock-write"]}' | jq
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/$USER_ID/roles" | jq

# Perfiles: catálogo, detalle, asignar y quitar
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/profiles" | jq
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/profiles/mto-maintenance-manager" | jq
curl -s -X PUT "$BASE/$USER_ID/profiles/mto-warehouse-viewer" -H "Authorization: Bearer $TOKEN" | jq
curl -s -X DELETE "$BASE/$USER_ID/profiles/mto-warehouse-viewer" -H "Authorization: Bearer $TOKEN" | jq

# Búsqueda por atributo (repetible; 400 si se mezcla con search o si se repite la clave)
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE?attribute=departamento:operaciones&attribute=turno:noche&max=50" | jq '.content[].username'

# Búsqueda inversa: quién tiene el perfil y quién tiene el rol suelto
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/profiles/mto-warehouse-viewer/users" | jq '.[].username'
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/roles/clients/mto-stock-api/stock-read/users?max=50" | jq '.[].username'

# Sesiones: ver, cerrar una y cerrar todas
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/$USER_ID/sessions" | jq
SESSION_ID=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/$USER_ID/sessions" | jq -r '.[0].id')
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/$USER_ID/sessions/$SESSION_ID" -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/$USER_ID/sessions" -H "Authorization: Bearer $TOKEN"

# Sesiones offline: ver y cerrar (lo que 'DELETE /sessions' no toca)
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/$USER_ID/offline-sessions" | jq
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/$USER_ID/offline-sessions" -H "Authorization: Bearer $TOKEN"

# Credenciales: ver y quitar el segundo factor de quien ha perdido el movil
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/$USER_ID/credentials" | jq
OTP_ID=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/$USER_ID/credentials" | jq -r '.[] | select(.type=="otp") | .id')
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/$USER_ID/credentials/$OTP_ID" -H "Authorization: Bearer $TOKEN"

# Borrar
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE/$USER_ID" -H "Authorization: Bearer $TOKEN"

# Un error: usuario inexistente → 404 application/problem+json
curl -s -i -H "Authorization: Bearer $TOKEN" "$BASE/00000000-0000-0000-0000-000000000000"
```

La misma colección, para el cliente HTTP del IDE, está en [`http/mto-users-api.http`](http/mto-users-api.http).

---

## Configuración

Todo se lee del entorno; [`.env.example`](.env.example) lo lista completo.

| Variable | Por defecto | Qué es |
|---|---|---|
| `KEYCLOAK_ISSUER_URI` | `http://auth.mto.local:8082/realms/mto` | Emisor de los tokens que acepta la API |
| `KEYCLOAK_USERS_CLIENT_ID` | `mto-users-api` | Cliente cuyos roles son los permisos de esta API |
| `KEYCLOAK_AUDIENCE_VALIDATION_ENABLED` | `true` | Exigir la audiencia |
| `KEYCLOAK_AUDIENCE` | `mto-users-api` | Audiencia exigida |
| `KEYCLOAK_AUTH_SERVER_URL` | `http://auth.mto.local:8082` | Raíz de Keycloak, **sin realm**, para la Admin API |
| `KEYCLOAK_REALM` | `mto` | Realm que se administra |
| `KEYCLOAK_ADMIN_CLIENT_ID` | `mto-users-svc` | Cliente confidencial con cuenta de servicio |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | *(vacío; `mto-users-svc-secret` en `dev`)* | Su secreto. Vacío deja arrancar y hace fallar la primera llamada con un 502 que dice por qué |
| `KEYCLOAK_CONNECT_TIMEOUT` / `KEYCLOAK_READ_TIMEOUT` / `KEYCLOAK_POOL_SIZE` | `2s` / `10s` / `10` | Cliente HTTP hacia Keycloak |
| `APP_PROFILES_PREFIX` / `APP_PROFILES_EXCLUDED` | `mto-` / *(vacío)* | Qué roles de realm son perfiles |
| `APP_SECURITY_EXPOSE_API_DOCS` | `false` (`true` en `dev`) | Swagger sin token |
| `APP_CORS_ALLOWED_ORIGIN` | `http://localhost:4200` | Origen permitido |
| `SERVER_PORT` | `8084` en `dev`, `8080` en `prod` | Puerto |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | Colector de trazas de `mto-platform` |

Perfiles de Spring: `dev` (por defecto), `test`, `prod`. En `prod` ninguna variable de Keycloak tiene
valor por defecto: un despliegue que administre el realm equivocado no debe arrancar.

---

## Arranque

### Local, desde el IDE o con Maven

1. Infraestructura y realm, desde `mto-platform` (los repositorios tienen que estar como hermanos):

   ```bash
   cd ../mto-platform
   cp .env.example .env
   docker compose up -d                       # Keycloak, colector de trazas... (sin aplicaciones)
   ./keycloak/apply-partials.sh               # clientes, roles, perfiles, usuarios de desarrollo y
                                              # los roles de realm-management de mto-users-svc
   ```

   Hace falta `127.0.0.1 auth.mto.local otel.mto.local` en el fichero de hosts: el `iss` de los
   tokens es esa URL.

2. La aplicación, con el perfil `dev` (puerto 8084, Swagger abierto, secreto de desarrollo):

   ```bash
   ./mvnw spring-boot:run
   curl -s http://localhost:8084/actuator/health
   ```

   Los usuarios de desarrollo `usuarios.lector`, `usuarios.gestor` y `usuarios.responsable`
   (contraseña `local`) llevan los perfiles `mto-users-viewer`, `mto-users-manager` y `mto-users-admin`.

### Con Docker

La imagen publicada la levanta `mto-platform`:

```bash
cd ../mto-platform
docker compose --profile users up -d               # solo mto-users (+ infraestructura)
docker compose --profile users --profile gateway up -d   # y el gateway, que publica /api/users
```

Para probar una construcción local contra esa misma infraestructura, el `compose.yaml` de este
repositorio trae solo la aplicación:

```bash
cp .env.example .env    # KEYCLOAK_ISSUER_URI, KEYCLOAK_ADMIN_CLIENT_SECRET y APP_CORS_ALLOWED_ORIGIN son obligatorias
docker compose up -d --build
curl -s http://localhost:8084/actuator/health
```

Y a mano, con la imagen ya construida:

```bash
docker build -t mto-users:local .
docker run --rm -p 8084:8080 \
  --add-host auth.mto.local:host-gateway --add-host otel.mto.local:host-gateway \
  -e KEYCLOAK_ISSUER_URI=http://auth.mto.local:8082/realms/mto \
  -e KEYCLOAK_USERS_CLIENT_ID=mto-users-api -e KEYCLOAK_AUDIENCE=mto-users-api \
  -e KEYCLOAK_AUTH_SERVER_URL=http://auth.mto.local:8082 -e KEYCLOAK_REALM=mto \
  -e KEYCLOAK_ADMIN_CLIENT_ID=mto-users-svc -e KEYCLOAK_ADMIN_CLIENT_SECRET=mto-users-svc-secret \
  -e APP_CORS_ALLOWED_ORIGIN=http://localhost:4200 \
  mto-users:local
```

---

## Build y tests

```bash
./mvnw compile
./mvnw test                                   # unitarios y slices; sin Docker
./mvnw verify                                 # ademas KeycloakUsersIT: Keycloak 26.1 real en Testcontainers (se salta sin Docker)
./mvnw test -Dtest=ApiAuthorizationRulesTest  # una clase
```

Una clase por capa: `SecurityLayerTest` (conversor de claims, audiencia, properties),
`ApiAuthorizationRulesTest` (cada verbo pide su permiso y ninguno implica otro),
`CorrelationIdFilterTest`, `KeycloakAdminClientGatewayTest` (traducción de errores con la cadena de
recursos simulada), `BusinessLayerTest` (servicios y auditoría), `MapperLayerTest`,
`RestControllerLayerTest` (contrato JSON y `problem+json`), `GlobalExceptionHandlerTest`,
`DtoValidationTest`, `MtoUsersApplicationTests` (el contexto entero sin Keycloak escuchando) y
`KeycloakUsersIT` (contra un Keycloak real: el ciclo de vida completo, que los seis roles de
`realm-management` bastan, que un perfil llega expandido en el token, que el filtro por atributo
combina con Y las claves distintas, que la lista de miembros de un rol no expande los compuestos,
que un login abre una sesión que la API cierra, que un token offline sobrevive al cierre de sesión y
solo muere al cerrar la suya, y que una credencial se quita por su id).

---

## Estructura

```
src/main/java/com/alejandro/mtousers/
├── MtoUsersApplication.java
├── configuration/
│   ├── keycloak/     KeycloakAdminProperties, KeycloakAdminClientConfiguration (bean Keycloak + gateway)
│   ├── profiles/     ProfileProperties (prefijo y exclusiones)
│   ├── security/     SecurityConfiguration, SecurityRoles, KeycloakJwtAuthenticationConverter, ...
│   └── web/          CorrelationIdFilter, OpenApiDocumentationConfiguration
├── controller/       UserController, RoleController, ProfileController
├── service/          UserService, RoleService, ProfileService, AdminAuditLog (+ impl/)
├── keycloak/         KeycloakAdminGateway (puerto) y KeycloakAdminClientGateway (admin client)
├── dto/              records de la API
├── mapper/           MapStruct: UserMapper, RoleMapper, ProfileMapper
└── exception/        excepciones de negocio y GlobalExceptionHandler (ProblemDetail)
keycloak/             mto-users-partial-import.json, mto-users-dev.json, README.md
http/                 mto-users-api.http
```
