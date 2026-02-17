package com.hispeedtriggercam.p40

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureServer(
    private val context: Context,
    port: Int = 80
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CaptureServer"
        private const val MIME_MP4 = "video/mp4"
        private const val MIME_JSON = "application/json"
        private const val MIME_HTML = "text/html"
    }

    private val RANGE_NOT_SATISFIABLE = object : Response.IStatus {
        override fun getRequestStatus() = 416
        override fun getDescription() = "416 Range Not Satisfiable"
    }

    private val captureDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "hispeed-trigger-cam"
        )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method != Method.GET) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Only GET is supported"
            )
        }

        return when {
            uri == "/captures" || uri == "/captures/" -> serveFileList(session)
            uri.startsWith("/capture/") -> serveFile(session, uri)
            uri == "/" -> {
                val response = newFixedLengthResponse(
                    Response.Status.REDIRECT,
                    MIME_HTML,
                    "<a href=\"/captures\">/captures</a>"
                )
                response.addHeader("Location", "/captures")
                response
            }
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found: $uri"
            )
        }
    }

    // ── GET /captures ───────────────────────────────────────

    private fun serveFileList(session: IHTTPSession): Response {
        val dir = captureDir
        val allowedExtensions = setOf("mp4", "json")
        val files = if (dir.exists() && dir.isDirectory) {
            dir.listFiles { f ->
                f.isFile && f.extension.lowercase() in allowedExtensions
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val json = buildString {
            append("[")
            files.forEachIndexed { index, file ->
                if (index > 0) append(",")
                append("{")
                append("\"name\":\"${file.name}\",")
                append("\"size\":${file.length()},")
                append("\"modified\":\"${dateFormat.format(Date(file.lastModified()))}\",")
                append("\"url\":\"/capture/${file.name}\"")
                append("}")
            }
            append("]")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json)
    }

    // ── GET /capture/{filename} ─────────────────────────────

    private fun serveFile(session: IHTTPSession, uri: String): Response {
        val filename = uri.removePrefix("/capture/")

        // Prevent path traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Invalid filename"
            )
        }

        val file = File(captureDir, filename)
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "File not found: $filename"
            )
        }

        val mime = when (file.extension.lowercase()) {
            "mp4" -> MIME_MP4
            "json" -> MIME_JSON
            else -> "application/octet-stream"
        }

        val fileLength = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader == null) {
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", fileLength.toString())
            return response
        }

        return servePartialContent(file, fileLength, rangeHeader, mime)
    }

    private fun servePartialContent(file: File, fileLength: Long, rangeHeader: String, mime: String = MIME_MP4): Response {
        val rangeValue = rangeHeader.removePrefix("bytes=").trim()
        val parts = rangeValue.split("-", limit = 2)

        val start: Long
        val end: Long

        try {
            start = parts[0].toLong()
            end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                parts[1].toLong().coerceAtMost(fileLength - 1)
            } else {
                fileLength - 1
            }
        } catch (e: NumberFormatException) {
            val response = newFixedLengthResponse(RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range")
            response.addHeader("Content-Range", "bytes */$fileLength")
            return response
        }

        if (start >= fileLength || start > end) {
            val response = newFixedLengthResponse(RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Range not satisfiable")
            response.addHeader("Content-Range", "bytes */$fileLength")
            return response
        }

        val contentLength = end - start + 1
        val fis = FileInputStream(file)
        var skipped = 0L
        while (skipped < start) {
            skipped += fis.skip(start - skipped)
        }

        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", contentLength.toString())
        return response
    }

    // ── IP Address Helper ───────────────────────────────────

    fun getDeviceIpAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    fun getServerUrl(): String? {
        val ip = getDeviceIpAddress() ?: return null
        val portSuffix = if (listeningPort == 80) "" else ":$listeningPort"
        return "http://$ip$portSuffix/captures"
    }
}
