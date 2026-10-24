package com.sukanth.leanlauncher.setup

import android.content.Context
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Why an on-device ADB action did not complete. */
enum class AdbFailure {
    /** No TCP listener answered on the TV itself. */
    UNREACHABLE,

    /** TCP connected, but the TV did not finish the debugging handshake. */
    AUTHORIZATION,

    /** The app could not create or read its debugging key. */
    KEY,

    /** The shell connection broke, or the home state could not be read. */
    COMMAND,
}

/**
 * Result of running the fixed command list over the TV's own ADB service.
 *
 * [failure] is null when the commands ran and the current home app was read, even if an
 * individual command was rejected. Callers compare [resolvedHome] with the package they
 * expected, because `cmd package set-home-activity` can report success without moving HOME.
 */
data class AdbOutcome(
    val failure: AdbFailure? = null,
    val detail: String? = null,
    val resolvedHome: String? = null,
    val output: String = "",
    val failedCommands: List<String> = emptyList(),
)

/**
 * Minimal ADB client that talks to this TV's own `adbd`, not to a computer.
 *
 * The first successful connection makes Android show the normal "Allow USB debugging?"
 * prompt. The app keeps its RSA key in private storage and only opens `shell:` streams for
 * the fixed commands it was given. There is no terminal and no arbitrary input path.
 *
 * This is deliberately optional: many TV firmwares expose ADB only on the LAN interface,
 * and some block loopback connections. [AdbFailure.UNREACHABLE] is the expected outcome
 * there, and the copy/paste fallback remains available.
 */
class OnDeviceAdb(context: Context) {

    private val appContext = context.applicationContext

    private val base64 = AdbBase64 { data ->
        Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * Runs [commands], then asks the TV which package currently owns HOME.
     *
     * Blocking: call from a background thread. Does not touch the UI.
     */
    fun execute(commands: List<String>): AdbOutcome {
        val crypto = runCatching { loadOrCreateCrypto() }.getOrElse { error ->
            return AdbOutcome(failure = AdbFailure.KEY, detail = error.message)
        }

        val link = try {
            connect(crypto)
        } catch (failure: ConnectFailure) {
            return AdbOutcome(failure = failure.failure, detail = failure.message)
        }

        val output = StringBuilder()
        val failedCommands = mutableListOf<String>()
        var resolvedHome: String? = null

        try {
            for (command in commands + AdbShellCommands.RESOLVE_HOME) {
                val result = runShell(link.connection, command)
                output.append("$ ").append(command).append('\n')
                if (result.output.isNotBlank()) {
                    output.append(result.output.trimEnd()).append('\n')
                }
                if (!result.ok) failedCommands += command
                if (command == AdbShellCommands.RESOLVE_HOME) {
                    resolvedHome = AdbShellCommands.resolvedPackage(result.output)
                }
            }
        } catch (error: Exception) {
            return AdbOutcome(
                failure = AdbFailure.COMMAND,
                detail = error.message,
                resolvedHome = resolvedHome,
                output = output.toString(),
                failedCommands = failedCommands,
            )
        } finally {
            link.close()
        }

        if (resolvedHome == null) {
            return AdbOutcome(
                failure = AdbFailure.COMMAND,
                detail = "The TV did not report its current home app.",
                output = output.toString(),
                failedCommands = failedCommands,
            )
        }

        return AdbOutcome(
            resolvedHome = resolvedHome,
            output = output.toString(),
            failedCommands = failedCommands,
        )
    }

    private fun connect(crypto: AdbCrypto): Link {
        var lastError: Exception? = null

        for (host in candidateHosts()) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, ADB_PORT), CONNECT_TIMEOUT_MS)
            } catch (error: Exception) {
                // No listener on this address. Try the next local address.
                lastError = error
                runCatching { socket.close() }
                continue
            }

            // TCP is open, so any further failure is the debugging handshake. Do not try
            // the other addresses: the same adbd would only prompt again.
            try {
                socket.soTimeout = AUTH_TIMEOUT_MS
                val connection = AdbConnection.create(socket, crypto)
                val ready = connection.connect(
                    AUTH_TIMEOUT_MS.toLong(),
                    TimeUnit.MILLISECONDS,
                    false,
                )
                if (!ready) {
                    runCatching { connection.close() }
                    throw ConnectFailure(
                        AdbFailure.AUTHORIZATION,
                        "The TV did not finish the ADB handshake.",
                    )
                }
                socket.soTimeout = COMMAND_TIMEOUT_MS
                return Link(connection, socket)
            } catch (failure: ConnectFailure) {
                runCatching { socket.close() }
                throw failure
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw ConnectFailure(AdbFailure.AUTHORIZATION, error.message)
            }
        }

        throw ConnectFailure(
            AdbFailure.UNREACHABLE,
            lastError?.message ?: "No local ADB service was found.",
        )
    }

    /**
     * The TV's own addresses. `127.0.0.1` covers the normal case; the interface
     * addresses cover firmware that binds `adbd` only to Wi-Fi or Ethernet.
     */
    private fun candidateHosts(): List<String> {
        val hosts = mutableListOf("127.0.0.1")

        runCatching { InetAddress.getByName("::1").hostAddress }
            .getOrNull()
            ?.let { if (it !in hosts) hosts += it }

        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        for (network in interfaces) {
            val usable = runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                address.hostAddress?.let { if (it !in hosts) hosts += it }
            }
        }

        return hosts
    }

    private fun runShell(connection: AdbConnection, command: String): ShellResult {
        // Legacy `shell:` streams do not carry an exit code. The marker makes a failed
        // command observable without switching to the binary shell-v2 protocol.
        val wrapped = "$command; echo $RC_MARKER\$?"
        val stream = connection.open("shell:$wrapped")
        val text = try {
            readStream(stream)
        } finally {
            runCatching { stream.close() }
        }

        var exitCode: Int? = null
        val clean = StringBuilder()
        text.lineSequence().forEach { line ->
            if (line.startsWith(RC_MARKER)) {
                exitCode = line.removePrefix(RC_MARKER).trim().toIntOrNull()
            } else {
                if (clean.isNotEmpty()) clean.append('\n')
                clean.append(line)
            }
        }

        return ShellResult(clean.toString().trim(), exitCode == 0)
    }

    private fun readStream(stream: AdbStream): String {
        val buffer = ByteArrayOutputStream()
        try {
            while (buffer.size() < MAX_OUTPUT_BYTES) {
                val chunk = stream.read() ?: break
                val room = MAX_OUTPUT_BYTES - buffer.size()
                if (chunk.size <= room) buffer.write(chunk) else buffer.write(chunk, 0, room)
            }
        } catch (_: IOException) {
            // Normal: adbd closes the stream when the shell command exits.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return buffer.toByteArray().toString(Charsets.UTF_8)
    }

    private fun loadOrCreateCrypto(): AdbCrypto {
        val privateKey = File(appContext.filesDir, PRIVATE_KEY_FILE)
        val publicKey = File(appContext.filesDir, PUBLIC_KEY_FILE)

        if (privateKey.isFile && publicKey.isFile) {
            runCatching { AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey) }
                .getOrNull()
                ?.let { return it }
            privateKey.delete()
            publicKey.delete()
        }

        val crypto = AdbCrypto.generateAdbKeyPair(base64)
        crypto.saveAdbKeyPair(privateKey, publicKey)
        return crypto
    }

    private class Link(val connection: AdbConnection, val socket: Socket) {
        fun close() {
            runCatching { connection.close() }
            runCatching { socket.close() }
        }
    }

    private class ConnectFailure(
        val failure: AdbFailure,
        message: String?,
    ) : IOException(message ?: "ADB connection failed")

    private data class ShellResult(val output: String, val ok: Boolean)

    private companion object {
        const val ADB_PORT = 5555
        const val CONNECT_TIMEOUT_MS = 2_500
        const val AUTH_TIMEOUT_MS = 60_000
        const val COMMAND_TIMEOUT_MS = 20_000
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val PRIVATE_KEY_FILE = "adbkey"
        const val PUBLIC_KEY_FILE = "adbkey.pub"
        const val RC_MARKER = "__LL_RC__:"
    }
}
