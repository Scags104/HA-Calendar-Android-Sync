package io.hacalsync.ha

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Minimal blocking wrapper around HA's WebSocket API. The sync adapter already
 * runs on a background thread, so request/response blocking is fine here.
 *
 * Used for calendar/event/create, calendar/event/update and calendar/event/delete,
 * which are the commands the HA frontend uses for editing calendars.
 */
class HaWebSocket(http: OkHttpClient, wsUrl: HttpUrl, token: String) : Closeable {

    private val inbox = LinkedBlockingQueue<JSONObject>()
    private var nextId = 1
    private val socket: WebSocket

    init {
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.put(JSONObject(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbox.put(JSONObject().put("type", "__failure").put("message", t.javaClass.simpleName))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.put(JSONObject().put("type", "__closed").put("message", "closed: $code $reason"))
            }
        })

        try {
            val hello = next()
            if (hello.optString("type") != "auth_required") throw IOException("Unexpected handshake: $hello")
            socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            val auth = next()
            when (auth.optString("type")) {
                "auth_ok" -> Unit
                "auth_invalid" -> throw HaAuthException("WebSocket auth rejected: ${auth.optString("message")}")
                else -> throw IOException("Unexpected auth reply: $auth")
            }
        } catch (e: Exception) {
            socket.cancel()
            throw e
        }
    }

    private fun next(): JSONObject {
        val msg = inbox.poll(30, TimeUnit.SECONDS) ?: throw IOException("Home Assistant WebSocket timed out")
        if (msg.optString("type").startsWith("__")) throw IOException(msg.optString("message"))
        return msg
    }

    /**
     * Sends a command and waits for its result.
     * @throws HaCommandException if HA answers success=false
     * @throws IOException on transport problems
     */
    fun call(type: String, payload: JSONObject): JSONObject {
        val id = nextId++
        payload.put("id", id).put("type", type)
        if (!socket.send(payload.toString())) throw IOException("WebSocket send failed")
        while (true) {
            val msg = next()
            if (msg.optInt("id", -1) != id || msg.optString("type") != "result") continue
            if (msg.optBoolean("success")) return msg.optJSONObject("result") ?: JSONObject()
            val err = msg.optJSONObject("error")
            throw HaCommandException("$type failed: ${err?.optString("code")} ${err?.optString("message")}")
        }
    }

    override fun close() {
        socket.close(1000, null)
    }
}
