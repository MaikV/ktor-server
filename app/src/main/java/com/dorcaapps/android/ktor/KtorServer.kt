package com.dorcaapps.android.ktor

import android.content.Context
import com.dorcaapps.android.ktor.datapersistence.LoginData
import com.dorcaapps.android.ktor.datapersistence.OrderType
import com.dorcaapps.android.ktor.dto.LoginDataDTO
import com.dorcaapps.android.ktor.dto.SessionCookie
import com.dorcaapps.android.ktor.extensions.asEncryptedFile
import com.dorcaapps.android.ktor.extensions.putDecryptedContentsIntoOutputStream
import com.dorcaapps.android.ktor.handler.AuthenticationHandler
import com.dorcaapps.android.ktor.handler.FileHandler
import com.dorcaapps.android.ktor.handler.bugtracker.Bugtracker
import com.dorcaapps.android.ktor.mapper.toDomainModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.sessions.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorServer @Inject constructor(
    @ApplicationContext
    private val appContext: Context,
    private val notificationHelper: NotificationHelper,
    private val fileHandler: FileHandler,
    private val authenticationHandler: AuthenticationHandler,
    private val serverEngine: ApplicationEngine,
    private val bugtracker: Bugtracker
) {
    private val coroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            bugtracker.trackThrowable(throwable)
        }
    private val coroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

    fun start() {
        serverEngine.start(false)
        setupApplicationEngine()
    }

    fun stop(gracePeriodMillis: Long, timeOutMillis: Long) {
        serverEngine.stop(gracePeriodMillis, timeOutMillis)
        coroutineScope.cancel()
    }

    fun confirmRegistration(loginData: LoginData) {
        coroutineScope.launch(Dispatchers.IO) {
            fileHandler.saveLoginData(loginData)
        }
    }

    private fun setupApplicationEngine() {
        serverEngine.application.apply {
            installFeatures(this)
            routing { installRoutes(this) }
        }
    }

    private fun installFeatures(application: Application): Unit = application.run {
        install(DefaultHeaders)
        install(AutoHeadResponse)
        install(PartialContent)
        install(Authentication, authenticationHandler.authenticationConfig)
        install(ContentNegotiation) {
            json()
        }
        install(Sessions) {
            cookie<SessionCookie>(SessionCookie.name)
        }
        install(CallLogging) {
            logger = LoggerFactory.getLogger("Application.Test")
            format {
                "\nRequest: ${it.request.toLogString()}\n    " +
                        it.request.headers.flattenEntries().joinToString("\n    ") +
                        "\nResponse: ${it.response.status()}\n    " +
                        it.response.headers.allValues().flattenEntries().joinToString("\n    ")
            }
        }
        install(StatusPages) {
            exception<Exception> {
                bugtracker.trackThrowable(it)
                throw it
            }
        }
    }

    private fun installRoutes(routing: Routing): Unit = routing.run {
        get("/") {
            call.respondText(
                "Hello World",
                ContentType.Text.Plain
            )
        }

        post("/register") {
            val loginData =
                call.receiveOrNull<LoginDataDTO>()?.toDomainModel() ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
            if (fileHandler.hasAccount(loginData.username)) {
                call.respond(HttpStatusCode.Conflict)
                return@post
            }
            notificationHelper.showRegisterNotification(loginData)
            call.respond(HttpStatusCode.OK)
        }

        authenticate(Constants.Authentication.LOGIN) {
            get("/login") {
                call.sessions.set(authenticationHandler.getNewSessionCookie())
                call.respond(HttpStatusCode.OK)
            }
        }

        authenticate(Constants.Authentication.SESSION) {
            route("/media") {
                route("/{id}") {
                    route("/thumbnail") { installMediaIdThumbnailRoutes(this) }
                    installMediaIdRoutes(this)
                }
                installMediaRoutes(this)
            }
        }
    }

    private fun installMediaIdThumbnailRoutes(route: Route): Unit = route.run {
        get {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val fileData = fileHandler.getFileData(id) ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val contentType = fileData.contentType

            val thumbnailFile = when (contentType.contentType) {
                ContentType.Video.Any.contentType, ContentType.Image.Any.contentType -> {
                    File(appContext.filesDir, fileData.thumbnailFilename).takeIf { it.exists() }
                }
                else -> null
            } ?: throw FileNotFoundException("ID: $id, FileName: ${fileData.thumbnailFilename}")

            call.respondOutputStream(contentType = ContentType.Image.PNG) {
                thumbnailFile.putDecryptedContentsIntoOutputStream(appContext, this)
            }
        }
    }

    @OptIn(ExperimentalIoApi::class)
    private fun installMediaIdRoutes(route: Route): Unit = route.run {
        delete {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            val didDelete = fileHandler.deleteFileDataWith(id)
            if (didDelete) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val fileData = fileHandler.getFileData(id) ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val file = File(appContext.filesDir, fileData.filename).takeIf { it.exists() }
                ?: throw FileNotFoundException("ID: $id, FileName: ${fileData.filename}")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileData.originalFilename
                ).toString()
            )

            call.respond(
                ByteReadChannelContentWithLength(
                    body = file.asEncryptedFile(appContext).openFileInput().toByteReadChannel(),
                    contentType = fileData.contentType,
                    contentLength = fileData.decryptedSize
                )
            )
        }
    }

    private fun installMediaRoutes(route: Route): Unit = route.run {
        get {
            val pageSize = call.parameters["pageSize"]?.toIntOrNull()?.takeIf { it >= 1 } ?: 10
            val page = call.parameters["page"]?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
            val orderType = OrderType.getWithDefault(call.parameters["order"]) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val result = fileHandler.getPagedMediaData(
                page = page,
                pageSize = pageSize,
                orderType = orderType
            )
            call.respond(result)
        }

        post {
            val originalMediaFilename = call.request.header(HttpHeaders.ContentDisposition)?.run {
                ContentDisposition.parse(this).parameter(ContentDisposition.Parameters.FileName)
            } ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val contentType = call.request.contentType()
            val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
            val creationDate = OffsetDateTime.now()
            val mediaFilename = "$creationDate#$originalMediaFilename"
            val thumbnailFilename = "$mediaFilename#thumbnail.png"
            val mediaFile = File(appContext.filesDir, mediaFilename)
            val thumbnailFile = File(appContext.filesDir, thumbnailFilename)
            when (contentType.contentType) {
                ContentType.Image.Any.contentType -> {
                    val bytes = call.receive<ByteArray>()
                    fileHandler.saveImageAndItsThumbnail(bytes, mediaFile, thumbnailFile)
                }
                ContentType.Video.Any.contentType -> {
                    fileHandler.saveVideoAndItsThumbnail(
                        call.receiveChannel(),
                        mediaFile,
                        thumbnailFile
                    )
                }
                else -> {
                    call.respond(HttpStatusCode.UnsupportedMediaType)
                    return@post
                }
            }
            fileHandler.addFileData(
                filename = mediaFilename,
                originalFilename = originalMediaFilename,
                thumbnailFilename = thumbnailFilename,
                creationDate = creationDate,
                encryptedSize = mediaFile.length(),
                decryptedSize = contentLength,
                contentType = contentType
            )
            call.respond(HttpStatusCode.OK)
        }
    }
}