---
paths:
  - "**/*.java"
  - "**/pom.xml"
---

# Java Stack & Conventions

## Stack
- Java 21+, Maven, IntelliJ IDEA, Lombok
- Spring Boot + Spring ecosystem (Data, Security, Web)
- ORM: Hibernate (default); EclipseLink only if already in project — never introduce it
- DB: PostgreSQL or MySQL; migrations via Flyway
- Messaging: Kafka, RabbitMQ; caching: Redis
- Auth: OAuth2, Keycloak; API docs: OpenAPI 3 (Swagger UI)
- Logging: SLF4J / Logback

## Architecture
- Medium/large projects: **hexagonal** (ports & adapters) — adapter/application/domain separation
- Small projects: layered MVC is fine
- Design patterns only where they genuinely fit

## JSON (Jackson)
- `@JsonInclude(JsonInclude.Include.NON_NULL)` on all response DTOs/records
- Dates: ISO-8601 (`LocalDateTime` → `"2025-04-04T10:15:30"`)
- Field naming: camelCase (Jackson default — do not override)

## Mapping (MapStruct)
- Use only when it genuinely simplifies development — not mandatory
- When used: `componentModel = "spring"`, interface-based, separate `*DtoMapper` / `*BodyMapper` per feature

---

## Layer Structure (Hexagonal)
```
adapter/in/web/
  controller/{feature}/
  dto/
  converter/ or mapper/
  advice/
application/service/{feature}/
domain/service/{feature}/
domain/exception/{feature}/
```

## Controllers
- `@RestController` + `@RequestMapping` at class level; `@Validated` at class level
- Constructor injection only — never `@Autowired` on fields
- Return `ResponseEntity<T>` with explicit status on every endpoint
- HTTP status: `200 OK` (GET/update), `201 CREATED` (POST), `204 NO_CONTENT` (DELETE), `202 ACCEPTED` (async)
- URL: `/api/v1/...` plural nouns; `ResponseEntity<Void>` for no-body responses
- Inject separate mappers for DTOs and request bodies — never map inside controllers

## OpenAPI Documentation
- Every endpoint: `@Operation(summary, description, tags, responses)`
- Every response: `@ApiResponse(responseCode, description, content)`
- Every parameter: `@Parameter(name, description, required, in)`
- Class level: `@Tag`; response content: `@Schema(implementation = ...)`; lists: `@ArraySchema`

## DTOs and Records
- Response DTOs → **Java Records** (immutable, no Lombok needed)
- Request bodies → `@Data @NoArgsConstructor @AllArgsConstructor` (Jackson needs setters)
- Domain request objects → `@Getter @AllArgsConstructor` (immutable)
- `@Builder` for complex request bodies; `@SuperBuilder` + `@EqualsAndHashCode(callSuper = true)` for inheritance
- `@JsonInclude(NON_NULL)` on all response records/DTOs
- Naming: `{Entity}DTO`, `{Entity}{Action}RequestBody`, `{Action}{Entity}Request`, `{Entity}ResponseDTO`

## Pagination
- Accept `Pageable` as controller parameter (Spring MVC auto-maps `?page=0&size=20&sort=...`)
- Return `PageResponse<T>` (custom wrapper) — never expose Spring's `Page<T>` in API contract
- Always paginate list endpoints that could return unbounded results

## Validation
- `@Valid` on `@RequestBody`; `@Validated` at controller class for path/query params
- JSR-380 (`@NotBlank`, `@NotNull`, `@Size`) with explicit messages; no custom validators unless needed

## Exception Handling
- Feature-specific `@ControllerAdvice` for hexagonal projects; single global handler for small ones
- One handler uses `@Order(Ordered.HIGHEST_PRECEDENCE)` — the global catch-all
- Per-feature: abstract base exception → specific exceptions (all `RuntimeException`)
- `404 NOT_FOUND` for `*NotFoundException`; `400 BAD_REQUEST` for validation/business errors

## Service Layer
- Interface: `{Entity}ServiceApi`; implementation: `{Entity}Service` with `@Service`
- Domain service interfaces in `domain/service/{feature}/` — these are the ports
- Pass domain request/response objects between layers — not raw params or HTTP DTOs
- `Optional.orElseThrow()` for null handling — no null returns from service methods
- No shared `BaseService` — inject cross-cutting concerns as dedicated components

## Transactions
- `@Transactional` on service layer only — never on controllers or repositories
- Read methods: `@Transactional(readOnly = true)`; write methods: `@Transactional`
- Annotate every public service method explicitly

## Hibernate & N+1
- Default lazy loading → N+1 risk; use `@EntityGraph` or `JOIN FETCH` when related data needed
- DTO projections (`SELECT new ...` or interface projections) for read-heavy endpoints
- Never iterate lazy collections outside `@Transactional`

## Security
- `@PreAuthorize("hasRole('ROLE_NAME')")` at method level on service or controller
- Spring Security filter chain for authentication; `@PreAuthorize` for authorization

## Spring Profiles
- Config files: `application-dev.yml`, `application-prod.yml`, `application-test.yml`
- Never hardcode environment-specific values — externalize to profile config

## Naming Conventions
| Element | Pattern | Example |
|---|---|---|
| Controller | `{Entity}Controller` | `FieldController` |
| Service interface | `{Entity}ServiceApi` | `FieldServiceApi` |
| Service impl | `{Entity}Service` | `FieldService` |
| DTO mapper | `{Entity}DtoMapper` | `FieldDtoMapper` |
| Body mapper | `{Entity}BodyMapper` | `FieldBodyMapper` |
| Exception handler | `{Entity}ExceptionHandler` | `FieldExceptionHandler` |
| Base exception | `{Entity}Exception` (abstract) | `FieldException` |
| Specific exception | `{Description}Exception` | `FieldNotFoundException` |
| Domain request | `{Action}{Entity}Request` | `CreateFieldRequest` |
| Domain response | `{Entity}ResponseDTO` | `CreateFieldResponseDTO` |
| Request body | `{Entity}{Action}RequestBody` | `FieldCreateRequestBody` |
| Constants | `SCREAMING_SNAKE_CASE` | `CLIENT_ID` |
