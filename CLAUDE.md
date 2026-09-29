# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MTO Users: a Spring Boot 4 / Java 25 REST API that administers the **users, client roles and
profiles** of the `mto` Keycloak realm through the **Keycloak Admin API**. Keycloak is the single
source of truth: there is no database and nothing is persisted here. The only thing that leaves is
one event per administrative action, with the person who did it, published best-effort to the
platform's RabbitMQ for `mto-notification` (see **Messaging**). `README.md` is the functional and
operational reference; `keycloak/README.md` explains the realm side (clients, the service account
and its `realm-management` roles).

⚠️ `mto-configuration`, `mto-stock`, `mto-maintenance`, `mto-notification` and `mto-gateway` are
**independent sibling repositories**. The local infrastructure (Keycloak, RabbitMQ, the trace
collector) is brought up by `mto-platform`, whose `keycloak/apply-partials.sh` applies this
repository's `keycloak/mto-users-partial-import.json` (after `mto-notification`'s, whose roles the
profiles here name) and grants the service account its roles. `compose.yaml` here holds **only the
application**.

## Commands

```bash
./mvnw compile
./mvnw test                                    # unit + slice tests, no Docker, no broker (app.rabbitmq.enabled=false in the test profile)
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
- `configuration/messaging` — `UsersMessagingProperties` (`app.rabbitmq.*`: the switch, the exchange,
  the publisher's queue capacity, confirm timeout, retry delays and drain timeout),
  `MessageSignatureProperties` (`app.messaging.signature.secret`) and `MessagingConfiguration`: the
  `NoOpUsersEventPublisher` when `app.rabbitmq.enabled=false`, otherwise the durable topic exchange,
  the envelope factory, the sender, the dispatcher and `RabbitUsersEventPublisher`. It refuses to
  start without publisher confirms on the connection factory.
- `configuration/profiles` — `ProfileProperties` (`app.profiles.prefix`, default `mto-`).
- `keycloak` — **`KeycloakAdminGateway` is the only door to Keycloak.** `KeycloakAdminClientGateway`
  wraps the admin client and translates every JAX-RS failure (`call(...)`): 404 → `UserNotFound`/
  `ClientNotFound`/`ProfileNotFound`, 409 → `UserAlreadyExists`, 400 → `KeycloakRequest`, 401/403 or
  OAuth client errors → `KeycloakAccess` (502), other `WebApplicationException` → `KeycloakUpstream`
  (502), `ProcessingException` → `KeycloakUnavailable` (503). `UsersQueryResource` is a custom
  JAX-RS proxy (`Keycloak.proxy`) because no `UsersResource.search` overload combines `search` with
  `enabled`/`emailVerified`. Nothing from `org.keycloak` leaves `keycloak/` and `mapper/`.
- `messaging` — the envelope shared with the siblings (`AsynchronousMessage`, `DomainEvent`,
  `MessageActor`/`MessageActorKind`), `UsersEventNames` (every `AdminAction` → entity, event name,
  routing key `mto.users.<entity>.<event>` and `eventType` `USERS_<ENTITY>_<EVENT>`),
  `UsersEventEnvelopeFactory` (built in the request thread: actor from `CurrentUserService`,
  `correlationId` from the MDC, `messageHash` over the seven original keys), `SensitiveKeys`,
  `MessagePayloadSignature`, `UsersEventSender` (one `send` + confirm + returned check),
  `UsersEventDispatcher` (bounded queue, one virtual thread, retries, loss metrics, drain on stop),
  `RabbitUsersEventPublisher`, `NoOpUsersEventPublisher`.
- `service` + `service/impl` — `UserService`, `RoleService`, `ProfileService` (package-private impls)
  and `AdminAuditLog` (one INFO line per mutation on logger `com.alejandro.mtousers.audit`, never a
  password or token, and then the event: `record(...)` is the only hook of the sixteen actions).
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
  `users-delete`, `users-roles-write`, `users-password-reset`, `users-profiles-write`,
  `users-sessions-write`, `users-credentials-write`, `ops-metrics`, `ops-write`. A new role goes to `SecurityRoles` **and**
  `keycloak/mto-users-partial-import.json` (and to `mto-platform/keycloak/mto-ops-cross-service.json`
  if it is an `ops-*` role). A route with more than one segment after `{userId}` needs its own
  `requestMatcher`: the `DELETE API + "/*"` rule does not reach `/{userId}/sessions/{sessionId}`.
- **Every `UsersException` subclass is named in an `@ExceptionHandler` list** of
  `GlobalExceptionHandler`; the `UsersException` handler is a 422 safety net, not a route.
  `GlobalExceptionHandlerTest` scans the package and fails if one is missing — calling a handler
  method directly in a test passes either way, so only that guard and `RestControllerLayerTest` see it.
- **What Keycloak drops in silence, this API rejects with 400** (`InvalidSearchException`): `search`
  together with `attribute` (Keycloak applies `search` and discards `q`), and a repeated attribute
  key (`q` is parsed into a map, so only the last pair survives). The role/profile member endpoints
  return **direct assignments only**, never expanded composites, and that is documented, not fixed.
- **Closing one session checks it belongs to that user first**: the Keycloak endpoint behind it
  (`DELETE /realms/{realm}/sessions/{id}`) belongs to the realm, so a foreign session id would close
  someone else's session. Offline sessions go through the same check with `offline=true`.
- **Offline sessions are a separate resource on purpose.** A token with `offline_access` opens no
  regular session, survives `DELETE /sessions`, and disabling the user only blocks the refresh while
  it lasts — re-enabling brings it back. Only closing the offline session revokes it, so taking
  somebody out is three calls (`PATCH /enabled`, `DELETE /sessions`, `DELETE /offline-sessions`) and
  that is documented rather than folded into one. Keycloak has no per-user offline session list:
  the clients to ask come from the user's consents (`Offline Token` grants).
- **A credential never carries its secret nor how it is stored**: Keycloak withholds `secretData`,
  and `credentialData` (hash algorithm and parameters) is dropped in the mapper. Removing one is
  audited with its type, which is why the service resolves it in the user's own list first.
- The service account uses the realm `mto` (`mto-users-svc`), never the `master` realm.
- Jackson 2 is on the classpath only for RESTEasy. The API is serialized with Jackson 3; do not add
  `spring-boot-jackson2`. The YAML/JAXB providers of the admin client are excluded on purpose.
- Passwords never reach a log: `CreateUserRequest`/`ResetPasswordRequest` hide them in `toString()`,
  and operation names passed to `call(...)` in the gateway must not include them.

### Messaging

- **One event per administrative action, through `AdminAuditLog.record(...)`**, published to the
  durable topic exchange `mto.users.exchange` **after** Keycloak answered. This service only
  publishes: it declares no queue (`mto-notification` owns `mto.notification.users.queue`, bound to
  `mto.users.#`). The contract (`UsersEventNames`, the `values` keys, the envelope) is consumed by
  `mto-notification`: keys are only **added**; renaming or removing one, or a routing key, breaks it.
  `README.md` (*Eventos hacia mto-notification*) documents it, and `docs/messaging/examples/` holds one
  real JSON per event shape, compared by `MessagingLayerTest` with the real factory: a new key
  changes the example in the same commit.
- **No outbox, on purpose**: there is no database to write it in. The publication is best effort
  and loud about what it loses: the envelope is built in the request thread (the only one with the
  token and the MDC), the HTTP response never waits for the broker, and the dispatcher retries with
  the configured delays and then logs a WARN and counts `users_events_lost_total{reason}`
  (`send-failed`, `queue-full`, `shutdown`). Keycloak's own admin event remains as the fallback the
  reader in `mto-notification` sees. Never make the response wait for the confirm and never block a
  Keycloak call on the broker.
- **Nothing that smells like a credential travels in `values`**: `SensitiveKeys` rejects the key
  (`password`, `secret`, `token`, `otp`...) at the envelope, so a detail key is named after the fact
  (`temporaryCredential`, `temporary`), never after the secret. `messageHash` covers the seven
  original keys only; `actor` and `correlationId` are outside it, like in `mto-configuration`.
- The broker is not in the health (`management.health.rabbit.enabled=false` by default) and the
  application starts without it; `app.rabbitmq.enabled=false` (the `test` profile) leaves only the
  `NoOpUsersEventPublisher`. Publisher confirms are mandatory: without them the context fails.

### Testing

One class per layer, add methods rather than classes: `SecurityLayerTest`, `ApiAuthorizationRulesTest`
(`@WebMvcTest` with the real controllers and mocked services), `CorrelationIdFilterTest`,
`KeycloakAdminClientGatewayTest` (mocked resource chain), `BusinessLayerTest` (mocked gateway, real
mappers, audit lines captured from Logback and the event of every action captured by a recording
publisher), `MessagingLayerTest` (the names, the envelope, the signature, the sender against a
mocked `RabbitTemplate`, the dispatcher driven with latches and a fake sleeper, the publisher, the
wiring with `ApplicationContextRunner` and the examples; its stub configuration is a
`@TestConfiguration` so the full-context test does not scan it), `MapperLayerTest`,
`RestControllerLayerTest`, `GlobalExceptionHandlerTest`, `DtoValidationTest`,
`MtoUsersApplicationTests` (full context, no Keycloak listening, no broker: the NoOp publisher) and
`KeycloakUsersIT` (failsafe; Keycloak 26.1 via Testcontainers with
`src/test/resources/keycloak/mto-users-test-realm.json`, which mirrors the shape of `keycloak/`).
