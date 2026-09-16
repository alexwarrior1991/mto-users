# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MTO Users: a Spring Boot 4 / Java 25 REST API that administers the **users, client roles and
profiles** of the `mto` Keycloak realm through the **Keycloak Admin API**. Keycloak is the single
source of truth: there is no database, no broker and nothing is persisted here. `README.md` is the
functional and operational reference; `keycloak/README.md` explains the realm side (clients, the
service account and its `realm-management` roles).

⚠️ `mto-configuration`, `mto-stock`, `mto-maintenance` and `mto-gateway` are **independent sibling
repositories**. The local infrastructure (Keycloak, the trace collector) is brought up by
`mto-platform`, whose `keycloak/apply-partials.sh` applies this repository's
`keycloak/mto-users-partial-import.json` and grants the service account its roles. `compose.yaml`
here holds **only the application**.

## Commands

```bash
./mvnw compile
./mvnw test                                    # unit + slice tests, no Docker
./mvnw verify                                  # + KeycloakUsersIT (real Keycloak 26.1 in Testcontainers, skipped without Docker)
./mvnw test -Dtest=ApiAuthorizationRulesTest   # one class
./mvnw spring-boot:run                         # dev profile: port 8084, Swagger open, dev secret
```

Local environment: `cd ../mto-platform && docker compose up -d && ./keycloak/apply-partials.sh`.
Profiles `dev` (default), `test`, `prod`. Local port **8084** (`dev`); the container listens on 8080
and `mto-platform` publishes it on 8084. Behind the gateway the public prefix is `/api/users`.

## Architecture

Packages under `com.alejandro.mtousers`:

- `configuration/security` — Keycloak resource server, same pieces as the siblings:
  `SecurityConfiguration` (route rules), `SecurityRoles` (constants), `KeycloakJwtAuthenticationConverter`
  (client roles → `ROLE_X`/`ROLE_CLIENT_X`, realm roles only `ROLE_REALM_X`), `JwtAudienceValidator`,
  `CurrentUserService`, `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler` (401/403 through the advice).
- `configuration/keycloak` — `KeycloakAdminProperties` (`app.keycloak.*`) and
  `KeycloakAdminClientConfiguration`: builds the `Keycloak` admin client bean (client_credentials with
  `mto-users-svc`, timeouts via `ResteasyClientBuilderImpl`) and the `KeycloakAdminGateway` bean.
- `configuration/web` — `CorrelationIdFilter` (`X-Correlation-Id` → MDC `correlationId`), OpenAPI.
- `configuration/profiles` — `ProfileProperties` (`app.profiles.prefix`, default `mto-`).
- `keycloak` — **`KeycloakAdminGateway` is the only door to Keycloak.** `KeycloakAdminClientGateway`
  wraps the admin client and translates every JAX-RS failure (`call(...)`): 404 → `UserNotFound`/
  `ClientNotFound`/`ProfileNotFound`, 409 → `UserAlreadyExists`, 400 → `KeycloakRequest`, 401/403 or
  OAuth client errors → `KeycloakAccess` (502), other `WebApplicationException` → `KeycloakUpstream`
  (502), `ProcessingException` → `KeycloakUnavailable` (503). `UsersQueryResource` is a custom
  JAX-RS proxy (`Keycloak.proxy`) because no `UsersResource.search` overload combines `search` with
  `enabled`/`emailVerified`. Nothing from `org.keycloak` leaves `keycloak/` and `mapper/`.
- `service` + `service/impl` — `UserService`, `RoleService`, `ProfileService` (package-private impls)
  and `AdminAuditLog` (one INFO line per mutation on logger `com.alejandro.mtousers.audit`, never a
  password or token).
- `controller` — `UserController`, `RoleController`, `ProfileController`, all under `/api/v1/users`
  (the gateway rewrites `/api/users` to it). Literal segments `roles`/`profiles` win over `{userId}`.
- `dto` — records; `mapper` — MapStruct (`MapStructCentralConfig` like the siblings);
  `exception` — business exceptions + `GlobalExceptionHandler` producing `ProblemDetail`
  (`application/problem+json`) with `errorCode`, `correlationId`, `timestamp`, `validationErrors`.

### Rules that must not be broken

- **Profiles are Keycloak composite realm roles** whose name starts with `app.profiles.prefix`.
  Assigning one is a realm role mapping; nothing is computed or stored locally. Do not reintroduce a
  YAML profile catalogue.
- **Protected clients** (`app.keycloak.protected-clients`: `realm-management`, `broker`, `account`...)
  are never listed nor assignable: `manage-users` lets the service account map any role, and this
  list is what keeps `users-roles-write` from granting `realm-admin`.
- **Permissions never imply each other** (`ApiAuthorizationRulesTest`): `users-read`, `users-write`,
  `users-delete`, `users-roles-write`, `users-password-reset`, `users-profiles-write`, `ops-metrics`,
  `ops-write`. A new role goes to `SecurityRoles` **and** `keycloak/mto-users-partial-import.json`
  (and to `mto-platform/keycloak/mto-ops-cross-service.json` if it is an `ops-*` role).
- The service account uses the realm `mto` (`mto-users-svc`), never the `master` realm.
- Jackson 2 is on the classpath only for RESTEasy. The API is serialized with Jackson 3; do not add
  `spring-boot-jackson2`. The YAML/JAXB providers of the admin client are excluded on purpose.
- Passwords never reach a log: `CreateUserRequest`/`ResetPasswordRequest` hide them in `toString()`,
  and operation names passed to `call(...)` in the gateway must not include them.

### Testing

One class per layer, add methods rather than classes: `SecurityLayerTest`, `ApiAuthorizationRulesTest`
(`@WebMvcTest` with the real controllers and mocked services), `CorrelationIdFilterTest`,
`KeycloakAdminClientGatewayTest` (mocked resource chain), `BusinessLayerTest` (mocked gateway, real
mappers, audit lines captured from Logback), `MapperLayerTest`, `RestControllerLayerTest`,
`GlobalExceptionHandlerTest`, `DtoValidationTest`, `MtoUsersApplicationTests` (full context, no
Keycloak listening) and `KeycloakUsersIT` (failsafe; Keycloak 26.1 via Testcontainers with
`src/test/resources/keycloak/mto-users-test-realm.json`, which mirrors the shape of `keycloak/`).
