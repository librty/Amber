package com.greenart7c3.nostrsigner.service

import android.content.Intent
import android.net.Uri
import android.provider.Browser
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.greenart7c3.nostrsigner.models.CompressionType
import com.greenart7c3.nostrsigner.models.IntentData
import com.greenart7c3.nostrsigner.models.Permission
import com.greenart7c3.nostrsigner.models.ReturnType
import com.greenart7c3.nostrsigner.models.SignerType
import com.greenart7c3.nostrsigner.service.model.AmberEvent
import com.vitorpamplona.quartz.crypto.KeyPair
import com.vitorpamplona.quartz.encoders.toHexKey
import com.vitorpamplona.quartz.events.Event
import java.net.URLDecoder

object IntentUtils {
    private fun getIntentDataWithoutExtras(data: String, intent: Intent, packageName: String?): IntentData {
        val localData = URLDecoder.decode(data.replace("nostrsigner:", "").split("?").first().replace("+", "%2b"), "utf-8")
        val parameters = data.replace("nostrsigner:", "").split("?").toMutableList()
        parameters.removeFirst()

        if (parameters.isEmpty() || parameters.toString() == "[]") {
            return getIntentDataFromIntent(intent, packageName)
        }

        var type = SignerType.SIGN_EVENT
        var pubKey = ""
        var compressionType = CompressionType.NONE
        var callbackUrl = ""
        var returnType = ReturnType.SIGNATURE
        parameters.joinToString("?").split("&").forEach {
            val params = it.split("=").toMutableList()
            val parameter = params.removeFirst()
            val parameterData = params.joinToString("=")
            if (parameter == "type") {
                type = when (parameterData) {
                    "sign_event" -> SignerType.SIGN_EVENT
                    "get_public_get" -> SignerType.GET_PUBLIC_KEY
                    "nip04_encrypt" -> SignerType.NIP04_ENCRYPT
                    "nip04_decrypt" -> SignerType.NIP04_DECRYPT
                    "nip44_encrypt" -> SignerType.NIP44_ENCRYPT
                    "nip44_decrypt" -> SignerType.NIP44_DECRYPT
                    else -> SignerType.SIGN_EVENT
                }
            }
            if (parameter == "pubkey") {
                pubKey = parameterData
            }
            if (parameter == "compressionType") {
                if (parameterData == "gzip") {
                    compressionType = CompressionType.GZIP
                }
            }
            if (parameter == "callbackUrl") {
                callbackUrl = parameterData
            }
            if (parameter == "returnType") {
                if (parameterData == "event") {
                    returnType = ReturnType.EVENT
                }
            }
        }

        return IntentData(
            localData,
            "",
            type,
            pubKey,
            "",
            callbackUrl,
            compressionType,
            returnType,
            listOf(),
            "",
            mutableStateOf(true),
            mutableStateOf(false),
            null
        )
    }

    private fun getIntentDataFromBunkerRequest(intent: Intent, bunkerRequest: BunkerRequest): IntentData {
        val type = when (bunkerRequest.method) {
            "connect" -> SignerType.CONNECT
            "sign_event" -> SignerType.SIGN_EVENT
            "get_public_get" -> SignerType.GET_PUBLIC_KEY
            "nip04_encrypt" -> SignerType.NIP04_ENCRYPT
            "nip04_decrypt" -> SignerType.NIP04_DECRYPT
            "nip44_encrypt" -> SignerType.NIP44_ENCRYPT
            "nip44_decrypt" -> SignerType.NIP44_DECRYPT
            else -> SignerType.SIGN_EVENT
        }

        val data = when (bunkerRequest.method) {
            "connect" -> "ack"
            "sign_event" -> {
                val amberEvent = AmberEvent.fromJson(bunkerRequest.params.first())
                AmberEvent.toEvent(amberEvent).toJson()
            }
            "nip04_encrypt", "nip04_decrypt", "nip44_encrypt", "nip44_decrypt" -> bunkerRequest.params.getOrElse(1) { "" }
            else -> ""
        }

        val pubKey = if (bunkerRequest.method.endsWith("encrypt") || bunkerRequest.method.endsWith("decrypt")) {
            bunkerRequest.params.first()
        } else {
            ""
        }
        val id = bunkerRequest.id

        return IntentData(
            data,
            "",
            type,
            pubKey,
            id,
            intent.extras?.getString("callbackUrl"),
            CompressionType.NONE,
            ReturnType.EVENT,
            listOf(),
            intent.extras?.getString("current_user") ?: "",
            mutableStateOf(true),
            mutableStateOf(false),
            bunkerRequest
        )
    }

    private fun getIntentDataFromIntent(intent: Intent, packageName: String?): IntentData {
        val type = when (intent.extras?.getString("type")) {
            "sign_event" -> SignerType.SIGN_EVENT
            "nip04_encrypt" -> SignerType.NIP04_ENCRYPT
            "nip04_decrypt" -> SignerType.NIP04_DECRYPT
            "nip44_decrypt" -> SignerType.NIP44_DECRYPT
            "nip44_encrypt" -> SignerType.NIP44_ENCRYPT
            "get_public_key" -> SignerType.GET_PUBLIC_KEY
            "decrypt_zap_event" -> SignerType.DECRYPT_ZAP_EVENT
            else -> SignerType.SIGN_EVENT
        }

        val data = try {
            if (packageName == null) {
                URLDecoder.decode(intent.data?.toString()?.replace("+", "%2b") ?: "", "utf-8").replace("nostrsigner:", "")
            } else {
                intent.data?.toString()?.replace("nostrsigner:", "") ?: ""
            }
        } catch (e: Exception) {
            intent.data?.toString()?.replace("nostrsigner:", "") ?: ""
        }

        val callbackUrl = intent.extras?.getString("callbackUrl") ?: ""
        val name = if (callbackUrl.isNotBlank()) Uri.parse(callbackUrl).host ?: "" else ""
        val pubKey = intent.extras?.getString("pubKey") ?: ""
        val id = intent.extras?.getString("id") ?: ""

        val compressionType = if (intent.extras?.getString("compression") == "gzip") CompressionType.GZIP else CompressionType.NONE
        val returnType = if (intent.extras?.getString("returnType") == "event") ReturnType.EVENT else ReturnType.SIGNATURE
        val listType = object : TypeToken<List<Permission>>() {}.type
        val permissions = Gson().fromJson<List<Permission>?>(intent.extras?.getString("permissions"), listType)
        permissions?.forEach {
            it.checked = true
        }

        return IntentData(
            data,
            name,
            type,
            pubKey,
            id,
            intent.extras?.getString("callbackUrl"),
            compressionType,
            returnType,
            permissions,
            intent.extras?.getString("current_user") ?: "",
            mutableStateOf(true),
            mutableStateOf(false),
            null
        )
    }

    fun getIntentData(intent: Intent, packageName: String?): IntentData? {
        if (intent.data == null) {
            return null
        }

        val bunkerRequest = if (intent.getStringExtra("bunker") != null) {
            BunkerRequest.mapper.readValue(
                intent.getStringExtra("bunker"),
                BunkerRequest::class.java
            )
        } else {
            null
        }

        return if (bunkerRequest != null) {
            getIntentDataFromBunkerRequest(intent, bunkerRequest)
        } else if (intent.extras?.getString(Browser.EXTRA_APPLICATION_ID) == null) {
            getIntentDataFromIntent(intent, packageName)
        } else {
            getIntentDataWithoutExtras(intent.data?.toString() ?: "", intent, packageName)
        }
    }
    fun getIntent(data: String, keyPair: KeyPair): Event {
        val event = AmberEvent.fromJson(data)
        if (event.pubKey.isEmpty()) {
            event.pubKey = keyPair.pubKey.toHexKey()
        }
        if (event.id.isEmpty()) {
            event.id = Event.generateId(
                event.pubKey,
                event.createdAt,
                event.kind,
                event.tags,
                event.content
            ).toHexKey()
        }

        return AmberEvent.toEvent(event)
    }
}
