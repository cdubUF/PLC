import java.io.IOException

data class HttpSuccess<T>(val status: Int, val body: T)

class HttpError(val status: Int, message: String, cause: Throwable? = null): Exception("${message} (status=${status})", cause) {
    constructor(status: Int, cause: Throwable) : this(status, cause.message ?: cause.javaClass.simpleName, cause)
    override fun equals(other: Any?) = status == (other as? HttpError)?.status //hacky, but for test equality
}

object UrlApi {

    // POST /urls {url: String, alias?: String}
    fun create(body: Map<String, String>): HttpSuccess<UrlModel> {
        // Body must contain only url (+ optional alias)
        val allowed = setOf("url", "alias")
        if (body.keys.any { it !in allowed }) throw HttpError(400, "Invalid fields")

        val url = body["url"] ?: throw HttpError(400, "Missing url")
        val alias = body["alias"]

        // Return 409 if alias conflicts with an existing one (spec change 9/20)
        if (alias != null && UrlModel.Companion.DB.aliases.containsKey(alias)) {
            throw HttpError(409, "Alias already in use")
        }

        try {
            val model = UrlModel.insert(url, alias) // may throw IllegalArgumentException or IOException
            return HttpSuccess(201, model)
        } catch (io: java.io.IOException) {
            throw HttpError(500, io)
        }
        // NOTE: IllegalArgumentException (contract/precondition error) is intentionally not caught
    }

    // GET /urls/:key
    fun read(key: String): HttpSuccess<UrlModel> {
        /*
        Approach:
        function read(key):
    # try to parse as integer
    id = parseIntOrNull(key)

    if id is not null:
        try:
            model = UrlModel.select(id)
            return HttpSuccess(200, model)
        catch NoSuchElementException:
            throw HttpError(404, "Not found")
        catch IOException:
            throw HttpError(500, "System failure")
        # IllegalArgumentException should bubble
    else:
        try:
            model = UrlModel.select(key)
            if model is null:
                throw HttpError(404, "Not found")
            return HttpSuccess(200, model)
        catch IOException:
            throw HttpError(500, "System failure")
        # IllegalArgumentException should bubble
        */
        // If key parses to Int, treat as id; otherwise treat as alias
        val id = key.toIntOrNull()
        return if (id != null) {
            try {
                val model = UrlModel.select(id) // may throw IllegalArgumentException, IOException, or NoSuchElementException
                HttpSuccess(200, model)
            } catch (e: NoSuchElementException) {
                throw HttpError(404, e.message ?: "Not found")
            } catch (io: java.io.IOException) {
                throw HttpError(500, io)
            }

        } else {
            try {
                val model = UrlModel.select(key) // may return null or throw IOException
                if (model == null) throw HttpError(404, "Not found")
                HttpSuccess(200, model)
            } catch (io: java.io.IOException) {
                throw HttpError(500, io)
            }
        }
    }

    // PATCH /urls/:id {alias: String}
    fun update(id: String, body: Map<String, String>): Result<HttpSuccess<UrlModel>> {
        /*
        Approach:
        id = parseIntOrNull(idStr)
        if id is null:
            return Failure(HttpError(400, "Invalid id"))

        if body.keys != {"alias"}:
            return Failure(HttpError(400, "Invalid fields"))

        alias = body["alias"]
        if alias is null:
            return Failure(HttpError(400, "Missing alias"))

        // UrlModel.update returns Result<UrlModel>
        r = UrlModel.update(id, alias)

        if r is Success(model):
            return Success(HttpSuccess(200, model))
        else:
            e = r.exception
            if e is NoSuchElementException:
                return Failure(HttpError(404, "Not found"))
            else if e is IOException:
                return Failure(HttpError(500, e))
            else if e is HttpError:
                return Failure(e)
            else:
                // e.g., IllegalArgumentException => bug/contract break
                throw e

         */
        val parsedId = id.toIntOrNull() ?: return Result.failure(HttpError(400, "Invalid id"))

        // body must be exactly {"alias": "..."}
        if (body.keys != setOf("alias")) return Result.failure(HttpError(400, "Invalid fields"))
        val alias = body["alias"] ?: return Result.failure(HttpError(400, "Missing alias"))
        // If alias == "" and UrlModel.update throws IllegalArgumentException

        return UrlModel.update(parsedId, alias).fold(
            onSuccess = { model -> Result.success(HttpSuccess(200, model)) },
            onFailure = { cause ->
                when (cause) {
                    is NoSuchElementException -> Result.failure(HttpError(404, cause.message ?: "Not found"))
                    is java.io.IOException      -> Result.failure(HttpError(500, cause))
                    is HttpError                -> Result.failure(cause)
                    else                        -> Result.failure(cause)
                }
            }
        )
    }
    // DELETE /urls/:id
    fun delete(id: String): Boolean {
        /*
        id = parseIntOrNull(idStr)
        if id is null:
            return false

        r = UrlModel.delete(id)

        if r is Success(flag):
            return flag
        else:
            e = r.exception
            if e is IOException:
                throw e      // maps to 500 upstream
            else:
                throw e      // let contract bugs bubble

         */
        val parsedId = id.toIntOrNull() ?: return false
        // Result<Boolean>: getOrThrow() will:
        //  - throw IOException (server error)
        //  - throw IllegalArgumentException (contract error) and let it bubble
        return UrlModel.delete(parsedId).getOrThrow()
    }
}


data class UrlModel(val id: Int, val url: String, val alias: String?) {

    companion object {

        object DB {
            internal var id: Int = 1
            internal val records = mutableMapOf<Int, UrlModel>()
            internal val aliases = mutableMapOf<String, UrlModel>()
            fun reset() { id = 1; records.clear(); aliases.clear() }
        }

        fun insert(url: String, alias: String?): UrlModel {
            require(url.isNotEmpty())
            require(alias == null || alias.isNotEmpty() && !DB.aliases.contains(alias))
            if (url == "error") {
                throw IOException("Simulated database error (url=error)")
            }
            val model = UrlModel(DB.id++, url, alias)
            DB.records[model.id] = model
            model.alias?.let { DB.aliases[it] = model }
            return model
        }

        fun select(id: Int): UrlModel {
            require(id >= -1)
            if (id == -1) {
                throw IOException("Simulated database error (id=-1)")
            }
            return DB.records[id] ?: throw NoSuchElementException("Record ${id} does not exist.")
        }

        fun select(alias: String): UrlModel? {
            require(alias.isNotEmpty())
            if (alias == "error") {
                throw IOException("Simulated database error (alias=error)")
            }
            return DB.aliases[alias]
        }

        fun update(id: Int, alias: String?): Result<UrlModel> {
            require(alias == null || alias.isNotEmpty() && DB.aliases[alias]?.id in listOf(null, id))
            if (alias == "error") {
                return Result.failure(IOException("Simulated database error (alias=error)"))
            }
            val current = DB.records[id] ?: return Result.failure(NoSuchElementException("Record ${id} does not exist."))
            val updated = current.copy(alias = alias)
            DB.records[updated.id] = updated
            current.alias?.let { DB.aliases.remove(it) }
            updated.alias?.let { DB.aliases[it] = updated }
            return Result.success(updated)
        }

        fun delete(id: Int): Result<Boolean> {
            require(id >= -1)
            if (id == -1) {
                return Result.failure(IOException("Simulated database error (id=-1)"))
            }
            val model = DB.records.remove(id)
            model?.alias?.let { DB.aliases.remove(it) }
            return Result.success(model != null);
        }

    }

}

fun main() {
    test("Create Url", listOf(
        "POST /urls {url:'url'}" to HttpSuccess(201, UrlModel(1, "url", null)),
    ))
    test("Create Alias", listOf(
        "POST /urls {url:'url',alias:'alias'}" to HttpSuccess(201, UrlModel(1, "url", "alias")),
    ))
    test("Create Invalid", listOf(
        "POST /urls {invalid:'invalid'}" to HttpError(400, "unused"),
    ))
    test("Create DB Error", listOf(
        "POST /urls {url:'error'}" to HttpError(500, "unused"),
    ))
    test("Create Contract Error", listOf(
        "POST /urls {url:''}" to IllegalArgumentException::class.java,
    ))
    test("Read Id", listOf(
        "POST /urls {url:'url'}" to HttpSuccess(201, UrlModel(1, "url", null)),
        "GET /urls/1" to HttpSuccess(200, UrlModel(1, "url", null)),
    ))
    test("Read Alias", listOf(
        "POST /urls {url:'url',alias:'alias'}" to HttpSuccess(201, UrlModel(1, "url", "alias")),
        "GET /urls/alias" to HttpSuccess(200, UrlModel(1, "url", "alias")),
    ))
    test("Read Missing", listOf(
        "GET /urls/alias" to HttpError(404, "unused"),
    ))
    test("Read DB Error", listOf(
        "GET /urls/error" to HttpError(500, "unused"),
    ))
    test("Read Contract Error", listOf(
        "GET /urls/-2" to IllegalArgumentException::class.java,
    ))
    test("Update", listOf(
        "POST /urls {url:'url'}" to HttpSuccess(201, UrlModel(1, "url", null)),
        "PATCH /urls/1 {alias:'new-alias'}" to Result.success(HttpSuccess(200, UrlModel(1, "url", "new-alias"))),
    ))
    test("Update Missing", listOf(
        "PATCH /urls/1 {alias:'new-alias'}" to Result.failure<HttpSuccess<UrlModel>>(HttpError(404, "unused")),
    ))
    test("Update Invalid", listOf(
        "PATCH /urls/1 {invalid:'invalid'}" to Result.failure<HttpSuccess<UrlModel>>(HttpError(400, "unused")),
    ))
    test("Update DB Error", listOf(
        "POST /urls {url:'url'}" to HttpSuccess(201, UrlModel(1, "url", null)),
        "PATCH /urls/1 {alias:'error'}" to Result.failure<Any>(HttpError(500, "unused")),
    ))
    test("Update Contract Error", listOf(
        "PATCH /urls/1 {alias:''}" to IllegalArgumentException::class.java,
    ))
    test("Delete", listOf(
        "POST /urls {url:'url'}" to HttpSuccess(201, UrlModel(1, "url", null)),
        "DELETE /urls/1" to true,
        "GET /urls/1" to HttpError(404, "unused"),
    ))
    test("Delete Missing", listOf(
        "DELETE /urls/1" to false,
    ))
    test("Delete Invalid", listOf(
        "DELETE /urls/invalid" to false,
    ))
    test("Delete DB Error", listOf(
        "DELETE /urls/-1" to IOException::class.java,
    ))
    test("Delete Contract Error", listOf(
        "DELETE /urls/-2" to IllegalArgumentException::class.java,
    ))
}

fun test(name: String, requests: List<Pair<String, Any>>) {
    UrlModel.Companion.DB.reset()
    val error = requests.firstNotNullOfOrNull { (request, expected) ->
        val result = Result.runCatching { execute(request) }.getOrElse { it }
        when {
            result == expected || result.javaClass == (expected as? Class<*>) -> null
            else -> """
            |
            |    - ${request}:
            |         expected ${expected}
            |         received ${result}${(result as? Exception)?.stackTrace?.first()?.let { " (${it})" } ?: ""}
            """.trimMargin("|")
        }
    }
    println(" - ${name}: ${if (error == null) "passed" else "failed"}${error ?: ""}")
}

private fun execute(request: String): Any {
    val split = request.split(" ")
    return when (split[0]) {
        "POST" -> {
            require(split.size == 3 && split[1] == "/urls")
            UrlApi.create(parseBody(split[2]))
        }
        "GET" -> {
            require(split.size == 2 && split[1].startsWith("/urls/"))
            UrlApi.read(split[1].removePrefix("/urls/"))
        }
        "PATCH" -> {
            require(split.size == 3 && split[1].startsWith("/urls/"))
            UrlApi.update(split[1].removePrefix("/urls/"), parseBody(split[2]))
        }
        "DELETE" -> {
            require(split.size == 2 && split[1].startsWith("/urls/"))
            UrlApi.delete(split[1].removePrefix("/urls/"))
        }
        else -> throw IllegalArgumentException("Unknown command ${split[0]}")
    }
}

private fun parseBody(string: String): Map<String, String> {
    require(string.matches(Regex("\\{([a-z]+:'[^']*?',?)*}")))
    return string.removeSurrounding("{", "}").split(",").associate {
        val (key, value) = it.split(":", limit = 2)
        key to value.removeSurrounding("'")
    }
}
