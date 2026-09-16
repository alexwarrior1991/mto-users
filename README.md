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
| `view-users`, `query-users` | Buscar, contar y leer usuarios y sus role-mappings |
| `manage-users` | Crear, modificar, habilitar, borrar, fijar contraseñas, enviar acciones por correo y **cambiar los role-mappings** de un usuario (realm y cliente) |
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
| Keycloak rechaza la petición (política de contraseñas, atributo obligatorio...) | 400 | `KC-400` |
| Roles de un cliente protegido | 400 | `ROL-PROTECTED-CLIENT` |
| Sin token / sin permiso | 401 / 403 | `AUTH-401` / `AUTH-403` |
| Usuario, cliente, rol o perfil inexistente | 404 | `USR-404`, `CLI-404`, `ROL-404`, `PRF-404` |
| Usuario duplicado (username o email) | 409 | `USR-409` |
| Keycloak rechaza la cuenta de servicio (secreto o roles de `realm-management`) | 502 | `KC-ACCESS` |
| Keycloak responde con un error inesperado (500 sin SMTP, por ejemplo) | 502 | `KC-502` |
| Keycloak no responde (conexión, timeout); lleva `Retry-After` | 503 | `KC-503` |
| Cualquier otra cosa, sin detalles | 500 | `APP-500` |

### Auditoría y correlación

Cada operación que modifica algo deja una línea en el logger `com.alejandro.mtousers.audit`:
`action=USER_CREATED actor=usuarios.responsable actorId=<sub> targetUserId=<id> detail=...`. El
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
| `/actuator/health`, `/actuator/info` | abiertos |
| resto de `/actuator/**` (lectura) | `ops-metrics` |
| `POST`/`DELETE /actuator/**` | `ops-write` |

Ningún permiso implica otro: `ApiAuthorizationRulesTest` lo comprueba verbo a verbo. Los perfiles
de la plataforma que los agrupan: `mto-users-viewer` (`users-read`), `mto-users-manager` (todo menos
borrar) y `mto-users-admin` (todo). `mto-ops` añade `users-read`, `ops-metrics` y `ops-write`.

---

## API

Raíz interna `/api/v1/users`; pública, a través del gateway, `/api/users`. OpenAPI en
`/v3/api-docs` y Swagger UI en `/swagger-ui.html` (abiertos con `APP_SECURITY_EXPOSE_API_DOCS=true`,
que es el valor del perfil `dev`).

### Usuarios

| Método y ruta | Qué hace |
|---|---|
| `GET /api/v1/users?search&username&email&enabled&emailVerified&first=0&max=20` | Búsqueda paginada por desplazamiento (`max` ≤ 200). `search` mira username, email, nombre y apellidos; `enabled` y `emailVerified` se combinan con cualquiera. Respuesta: `{content, first, max, total}` |
| `POST /api/v1/users` | Alta. `201` + `Location`. Opcionales `temporaryPassword` (Keycloak obliga a cambiarla al entrar) y `requiredActions`. Habilitado salvo `enabled=false` |
| `GET /api/v1/users/{userId}` | Detalle |
| `PUT /api/v1/users/{userId}` | Datos básicos (`firstName`, `lastName`, `email`, `emailVerified`, `attributes`). Lo que no viene se conserva |
| `PATCH /api/v1/users/{userId}/enabled` | `{"enabled": true\|false}` |
| `DELETE /api/v1/users/{userId}` | `204` |
| `POST /api/v1/users/{userId}/reset-password` | `{"password", "temporary"}` (temporal por defecto). `204` |
| `POST /api/v1/users/{userId}/execute-actions-email` | `{"actions": [...], "lifespanSeconds"?, "clientId"?, "redirectUri"?}`. `202`. Necesita SMTP en el realm |

### Roles

| Método y ruta | Qué hace |
|---|---|
| `GET /api/v1/users/roles/clients` | Clientes cuyos roles se pueden asignar (los protegidos no aparecen) |
| `GET /api/v1/users/roles/clients/{clientId}` | Roles de un cliente |
| `GET /api/v1/users/{userId}/roles` | Roles asignados **directamente**: `{realmRoles, clientRoles: [{clientId, roles}]}` |
| `PUT /api/v1/users/{userId}/roles/clients/{clientId}` | `{"roles": [...]}`. Añade; devuelve las asignaciones actualizadas |
| `DELETE /api/v1/users/{userId}/roles/clients/{clientId}` | `{"roles": [...]}` en el cuerpo. Quita; devuelve las asignaciones actualizadas |

### Perfiles

| Método y ruta | Qué hace |
|---|---|
| `GET /api/v1/users/profiles` | Perfiles de la plataforma (roles de realm `mto-*`) |
| `GET /api/v1/users/profiles/{profileName}` | Qué concede: `{name, description, clientRoles: [{clientId, roles}], realmRoles}` |
| `GET /api/v1/users/{userId}/profiles` | Perfiles del usuario |
| `PUT /api/v1/users/{userId}/profiles/{profileName}` | Asigna (idempotente); devuelve los perfiles del usuario |
| `DELETE /api/v1/users/{userId}/profiles/{profileName}` | Quita; devuelve los perfiles del usuario |

`roles` y `profiles` son segmentos literales y Spring MVC los resuelve antes que `{userId}`; los
ids de usuario se validan (`[A-Za-z0-9:._-]{1,255}`) antes de llamar a Keycloak.

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
`KeycloakUsersIT` (el ciclo de vida completo contra un Keycloak real: comprueba que los seis roles de
`realm-management` bastan y que un perfil llega expandido en el token).

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
