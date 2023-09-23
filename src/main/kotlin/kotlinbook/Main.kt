package kotlinbook

import com.google.gson.Gson
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.html.Entities
import kotlinx.html.InputType
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.lang.foreign.MemorySegment.copy
import javax.sql.DataSource
import kotlin.reflect.full.declaredMemberProperties


private val log = LoggerFactory.getLogger("kotlinbook.Main")

fun createAppConfig(env: String) =
    ConfigFactory
        .parseResources("app-${env}.conf")
        .withFallback(ConfigFactory.parseResources("app.conf"))
        .resolve()
        .let {
            WebappConfig(
                httpPort = it.getInt("httpPort"),
                dbUser = it.getString("dbUser"),
                dbPassword = it.getString("dbPassword"),
                dbUrl = it.getString("dbUrl")
            )
        }
fun createDataSource(config: WebappConfig) =
    HikariDataSource().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
       password = config.dbPassword
    }
fun main() {
    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"
    val config = createAppConfig(env)
    createAndMigrateDataSource(config)

    val secretsRegex = "password|secret|key"
        .toRegex(RegexOption.IGNORE_CASE)
    WebappConfig::class.declaredMemberProperties
        .sortedBy { it.name }
        .map {
            if (secretsRegex.containsMatchIn(it.name)) {
                "${it.name} = ${it.get(config).toString().take(2)}*****"
            } else {
                "${it.name} = ${it.get(config)}"
            }
        }
        .joinToString(separator = "\n")
    val dataSource = createDataSource(config)
    dataSource.getConnection().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1")
        }
    }
    log.debug("Testing my logger")
    embeddedServer(Netty, port = 4207) {
        createKtorApplication()
    }.start(wait = true)
}
fun Application.createKtorApplication() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            kotlinbook.log.error("An unknown error occurred", cause)
            call.respondText(
                text = "500: $cause",
                status = HttpStatusCode.InternalServerError
            )
        }
    }
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/", webResponse {
            TextWebResponse("Hello, world!")
        })
        get("/param_test", webResponse {
            TextWebResponse(
                "The param is: ${call.request.queryParameters["foo"]}"
            )
        })
        get("/json_test", webResponse {
            JsonWebResponse(mapOf("foo" to "bar"))
        })
        get("/json_test_with_header", webResponse {
            JsonWebResponse(mapOf("foo" to "bar"))
                .header("X-Test-Header", "Just a test!")
        })

    }
}
data class WebappConfig(
    val httpPort: Int,
    val dbUser: String,
    val dbPassword: String,
    val dbUrl: String


)
sealed class WebResponse {
    abstract val statusCode: Int
    abstract val headers: Map<String, List<String>>
    abstract fun copyResponse(
        statusCode: Int,
        headers: Map<String, List<String>>)
            : WebResponse
    fun header(headerName: String, headerValue: String) =
        header(headerName, listOf(headerValue))

    fun header(headerName: String, headerValue: List<String>) = copyResponse(
        statusCode,
        headers.plus(Pair(
            headerName,
            headers.getOrDefault(headerName, listOf())
                .plus(headerValue)
        ))
    )
    fun headers(): Map<String, List<String>> =
        headers
            .map { it.key.lowercase() to it.value }
            .fold(mapOf()) { res, (k, v) ->
                res.plus(Pair(
                    k,
                    res.getOrDefault(k, listOf()).plus(v)
                ))
            }

    
}
data class TextWebResponse(
    val body: String,
    override val statusCode: Int = 200,
    override val headers: Map<String, List<String>> = mapOf()


) : WebResponse() {
    override fun copyResponse(statusCode: Int, headers: Map<String, List<String>>): WebResponse = copy(body, statusCode, headers)
}

data class JsonWebResponse(
    val body: Any?,
    override val statusCode: Int = 200,
    override val headers: Map<String, List<String>> = mapOf()
) : WebResponse()
{
    override fun copyResponse(statusCode: Int, headers: Map<String, List<String>>): WebResponse = copy(body, statusCode, headers)

}
class KtorJsonWebResponse (
    val body: Any?,
    override val status: HttpStatusCode = HttpStatusCode.OK
) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType =
        ContentType.Application.Json.withCharset(Charsets.UTF_8)
    override fun bytes() = Gson().toJson(body).toByteArray(
        Charsets.UTF_8
    )
}
fun webResponse(
    handler: suspend PipelineContext<Unit, ApplicationCall>.(
    ) -> WebResponse
): PipelineInterceptor<Unit, ApplicationCall> {
    return {
        val resp = this.handler()


        for ((name, values) in resp.headers())
            for (value in values)
                call.response.header(name, value)
        val statusCode = HttpStatusCode.fromValue(
            resp.statusCode
        )
        when (resp) {
            is TextWebResponse -> {
                call.respondText(
                    text = resp.body,
                    status = statusCode
                )
            }
            is JsonWebResponse ->
                {
                    call.respond(KtorJsonWebResponse(body =resp.body,status = statusCode))
                }
        }
    }
}
fun migrateDataSource(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("db/migration")
        .table("flyway_schema_history")
        .load()
        .migrate()
}

fun createAndMigrateDataSource(config: WebappConfig) =
    createDataSource(config).also(::migrateDataSource)