# Lo que `mto-users` aporta al realm

Dos ficheros, que aplica `mto-platform/keycloak/apply-partials.sh` en su sitio del orden de ensamblado:

| Fichero | Qué contiene | Dónde vale |
|---|---|---|
| `mto-users-partial-import.json` | Los clientes `mto-users-api` y `mto-users-svc`, los permisos (roles de cliente) y los perfiles `mto-users-*` | Cualquier entorno |
| `mto-users-dev.json` | El secreto local de `mto-users-svc` y tres usuarios de desarrollo | Solo local |

## Qué hay dentro

| Cliente | Tipo | Para qué |
|---|---|---|
| `mto-users-api` | Resource server (sin flujos) | Declara los permisos como roles de cliente y es la audiencia de los tokens que acepta la API |
| `mto-users-svc` | Confidencial con cuenta de servicio (`client_credentials`) | Con él `mto-users` llama a la Admin API **de este mismo realm**. No hay ninguna credencial del realm `master` |

Permisos (roles del cliente `mto-users-api`): `users-read`, `users-write`, `users-delete`,
`users-roles-write`, `users-password-reset`, `users-profiles-write`, `users-sessions-write`,
`ops-metrics`, `ops-write`. Ninguno implica otro: `users-sessions-write` —cerrar las sesiones
abiertas de alguien— no lo dan ni `users-write` ni `users-delete`.

Perfiles (roles compuestos de realm): `mto-users-viewer` (`users-read`), `mto-users-manager`
(todo menos `users-delete`) y `mto-users-admin` (todo). `mto-ops`, que vive en `mto-platform`,
añade `users-read`, `ops-metrics` y `ops-write`.

## Los roles de `realm-management` de la cuenta de servicio

Una importación parcial **no asigna roles a la cuenta de servicio de un cliente**. Por eso el partial
import crea `mto-users-svc` pero sus roles se conceden aparte:

| Rol de `realm-management` | Lo necesita para |
|---|---|
| `view-users` | Leer usuarios, sus role-mappings, sus sesiones abiertas y quién tiene un rol o un perfil |
| `query-users` | Buscar y contar usuarios, también por atributo |
| `manage-users` | Crear, modificar, habilitar/deshabilitar, borrar, fijar contraseñas, enviar acciones por correo, cerrar sesiones y cambiar los role-mappings (de realm y de cliente) de un usuario |
| `view-clients` | Leer clientes y los roles de cada uno (resolver `clientId` → UUID) |
| `query-clients` | Listar clientes |
| `view-realm` | Leer los roles de realm (los perfiles) y sus compuestos |

Son los seis, y solo esos. En particular **no** lleva `manage-realm`, `manage-clients` ni
`realm-admin`: la API no crea roles ni clientes, solo los asigna. `KeycloakUsersIT` levanta un
Keycloak real con exactamente estos seis y recorre toda la API: si faltara uno, saldría un 403 de
Keycloak traducido a `KeycloakAccessException`.

- **En local** los concede `mto-platform/keycloak/apply-partials.sh` por la Admin API
  (`conceder_roles_de_servicio mto-users-svc realm-management ...`), igual que concede
  `stock-read`/`stock-write` a `mto-maintenance-svc`. Es reejecutable.
- **En un entorno desplegado** se hace en la consola: Clients → `mto-users-svc` → *Service accounts
  roles* → *Assign role* → filtrar por clientes → `realm-management` → los seis de la tabla. Y el
  secreto se copia de Clients → `mto-users-svc` → *Credentials* a `KEYCLOAK_ADMIN_CLIENT_SECRET`.

Ojo con lo que `manage-users` permite: en Keycloak basta para mapear **cualquier** rol a un usuario,
`realm-management/realm-admin` incluido. Es el servicio quien se niega a tocar los roles de los
clientes de `app.keycloak.protected-clients`; ese es el límite real de `users-roles-write`.

## Cómo cargarlo

```bash
cd ../mto-platform && ./keycloak/apply-partials.sh                # todo, con usuarios de desarrollo
cd ../mto-platform && ./keycloak/apply-partials.sh --no-dev-users # solo clientes, roles y perfiles
```

A mano, contra cualquier Keycloak (el fichero no lleva `ifResourceExists`, así que un cliente que ya
exista hace fallar la importación; el script de la plataforma lo aplica con `OVERWRITE`):

```bash
TOKEN=$(curl -s -X POST http://localhost:8082/realms/master/protocol/openid-connect/token \
  -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin | jq -r .access_token)
curl -s -X POST http://localhost:8082/admin/realms/mto/partialImport \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data-binary @keycloak/mto-users-partial-import.json
```

## Los atributos de usuario dependen del realm, no de esta parcial

El campo `attributes` de la API solo se guarda si el perfil de usuario declarativo del realm admite
atributos no gestionados (`unmanagedAttributePolicy`). Keycloak 26 los descarta en silencio por
defecto. Eso es configuración **del realm**, no de un cliente, así que no cabe en una importación
parcial: lo trae el realm base de `mto-platform` (`keycloak/mto-realm.json`, con `ADMIN_EDIT`). El
realm de `KeycloakUsersIT` lleva la misma configuración y es donde se comprueba.

Al declararla hay que repetir los cuatro atributos del perfil por defecto (`username`, `email`,
`firstName`, `lastName`): un `kc.user.profile.config` que solo lleve la política deja el perfil sin
atributos y Keycloak empieza a descartar `firstName` y `lastName` sin avisar.

## Después de importar

1. Conceder a `mto-users-svc` los seis roles de `realm-management` (arriba).
2. Copiar el secreto de `mto-users-svc` a `KEYCLOAK_ADMIN_CLIENT_SECRET`.
3. Que `mto-frontend` (en el realm base de `mto-platform`) tenga el *audience mapper*
   `audiencia-mto-users-api`: sin él ningún token del frontal nombra a esta API en `aud` y todo
   responde 401 (ver *el error más fácil de cometer* en `mto-stock/keycloak/README.md`).
4. Dar a las personas un perfil: `mto-users-viewer`, `mto-users-manager` o `mto-users-admin`.

## Usuarios de desarrollo (`mto-users-dev.json`)

Contraseña `local` para los tres:

| usuario | perfil |
|---|---|
| `usuarios.lector` | `mto-users-viewer` |
| `usuarios.gestor` | `mto-users-manager` |
| `usuarios.responsable` | `mto-users-admin` |
