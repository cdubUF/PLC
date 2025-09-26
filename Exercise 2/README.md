# URL API – Error Handling Assignment

This project implements a simple URL API with endpoints to **create, read, update, and delete** URL resources. The main focus is on practicing different **error handling models** in Kotlin, relating them to concepts from software engineering and HTTP semantics.

---

## 📚 Concepts Applied

- **Logical vs. System Errors**
  - *Logical errors*: bad input, broken preconditions → mapped to 400/404/409.
  - *System errors*: external failures (I/O, DB) → mapped to 500.
  - *Unrecoverable bugs* (`IllegalArgumentException`) → not caught, allowed to bubble.

- **Recoverable vs. Unrecoverable**
  - Recoverable errors can be retried (e.g., alias conflict, network hiccup).
  - Unrecoverable errors are programming mistakes or contract violations.

- **Error Models**
  - **Exceptions**: thrown for unexpected or unrecoverable failures.
  - **Result types**: explicit success/failure values, useful for composition.

---

## 🚀 Endpoints

### 1. `create(body: Map<String, String>): HttpSuccess<UrlModel>`
**POST /urls**

- Validates input keys (`url` required, `alias` optional).
- `400` → invalid fields / missing url.
- `409` → alias already in use.
- `500` → I/O failure (caught).
- `IllegalArgumentException` → bubbles (programmer bug).
- ✅ Success → `201 Created` with new URL model.

---

### 2. `read(key: String): HttpSuccess<UrlModel>`
**GET /urls/:key**

- If `key` is integer → lookup by id.
- Else → lookup by alias.
- `404` → not found.
- `500` → I/O failure.
- `IllegalArgumentException` → bubbles.
- ✅ Success → `200 OK` with URL model.

---

### 3. `update(id: String, body: Map<String, String>): Result<HttpSuccess<UrlModel>>`
**PATCH /urls/:id**

- `id` must be integer → else failure(400).
- `body` must be exactly `{ "alias": "..." }`.
- `404` → not found.
- `500` → I/O failure.
- `HttpError` → passed through.
- `IllegalArgumentException` → bubbles.
- ✅ Success → `Result.success(HttpSuccess(200, updatedModel))`.

---

### 4. `delete(id: String): Boolean`
**DELETE /urls/:id**

- `id` must be integer → else return `false`.
- On success → return Boolean from DB.
- On I/O failure → throw (maps to 500).
- On programmer bug → bubble.
- ✅ Success → true/false depending on DB result.

---

## 🔀 Error Flow Diagram

      +-------------------+
      |  Client Request   |
      +-------------------+
                |
                v
    +-----------------------+
    | Input Validation      |
    +-----------------------+
      | invalid/missing url
      v
   HttpError 400

                |
                v
    +-----------------------+
    | Alias Conflict Check  |
    +-----------------------+
      | alias exists
      v
   HttpError 409

                |
                v
    +-----------------------+
    | DB / IO Operation     |
    +-----------------------+
      | IOException
      v
   HttpError 500

      | IllegalArgumentException
      v
   (bubble → programmer bug)

      | Success
      v
HttpSuccess (200 / 201)
