---
paths:
  - "**/*Test.java"
  - "**/test/**/*.java"
---

# Java Testing Conventions

## Test Layers
| Layer | Annotation | Tests | DB |
|---|---|---|---|
| Controller | `@WebMvcTest` | HTTP contract, validation, error mapping | None (mock service) |
| Repository | `@DataJpaTest` | Queries, mappings, constraints | Testcontainers (preferred) or H2 |
| Integration | `@SpringBootTest` | Full flow end-to-end | Testcontainers (preferred) or H2 |
| Unit | Plain JUnit 5 | Service logic, domain logic | None |

- **Testcontainers** preferred for any test hitting a DB — real PostgreSQL/MySQL container
- **H2** acceptable only for lightweight cases with no PostgreSQL-specific features
- **Never H2** when using JSONB, arrays, `ON CONFLICT`, or custom PG functions

## BDD Style (JUnit 5 + BDDMockito + AssertJ)
- Structure: `given(...)` → `when` → `then` — no Cucumber
- Method naming: `shouldDoX_whenY()` or descriptive sentence

```java
@Test
void shouldReturnField_whenFieldExists() {
    // given
    given(fieldService.getById("field-123")).willReturn(fieldDto);

    // when
    ResponseEntity<FieldDTO> response = controller.getFieldInfo("field-123");

    // then
    then(response.getStatusCode()).isEqualTo(OK);
    then(response.getBody().id()).isEqualTo("field-123");
}
```

## General
- Approach: **TDD** — write tests first, make them pass, refactor
- `@ActiveProfiles("test")` on all test classes
- Test-specific config in `application-test.yml`
