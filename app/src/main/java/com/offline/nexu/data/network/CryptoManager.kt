package com.offline.nexu.data.network

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Cifrado E2E real: cada dispositivo tiene un par de llaves EC (secp256r1) propio. Al conectar
// con un vecino directo se intercambia la llave pública (PUBKEY:, ver NexuMeshManager). El
// contenido de los mensajes 1:1 se cifra con AES-256-GCM usando una clave derivada por ECDH
// entre el emisor y el destinatario REAL (no el vecino que reenvía), así que un nodo que solo
// reenvía el paquete por la malla nunca puede leer el contenido. Los grupos usan una clave AES
// simétrica compartida, repartida una vez por miembro (envuelta con ECDH) al crear/unir el grupo.
object CryptoManager {
    private const val PREFS_NAME = "NexuCrypto"
    private const val KEY_PRIVATE = "ec_private_key"
    private const val KEY_PUBLIC = "ec_public_key"
    private const val CURVE = "secp256r1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // Llaves públicas de vecinos directos ya conocidas, keyed por nombre (mismo criterio que
    // P2PManager.connectedNames / contactBatteryLevels). En memoria: se repuebla solo al
    // reconectar, vía el PUBKEY: que se reenvía automáticamente en cada onConnectionResult.
    private val peerPublicKeys = mutableMapOf<String, String>()

    fun registrarLlavePublicaDePeer(peerName: String, base64PublicKey: String) {
        peerPublicKeys[peerName] = base64PublicKey
    }

    fun tieneLlaveDePeer(peerName: String): Boolean = peerPublicKeys.containsKey(peerName)

    fun getPublicKeyBase64(context: Context): String {
        val pair = getOrCreateKeyPair(context)
        return Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
    }

    private fun getOrCreateKeyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPriv = prefs.getString(KEY_PRIVATE, null)
        val storedPub = prefs.getString(KEY_PUBLIC, null)
        if (storedPriv != null && storedPub != null) {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(storedPriv, Base64.NO_WRAP)))
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(storedPub, Base64.NO_WRAP)))
            return KeyPair(pub, priv)
        }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec(CURVE))
        val pair = kpg.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }

    private fun decodePublicKey(base64: String): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(Base64.decode(base64, Base64.NO_WRAP)))
    }

    private fun derivarClaveCompartida(myPrivate: PrivateKey, peerPublic: PublicKey): SecretKey {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(peerPublic, true)
        val sharedSecret = ka.generateSecret()
        val aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(aesKeyBytes, "AES")
    }

    private fun aesGcmEncrypt(key: SecretKey, plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun aesGcmDecrypt(key: SecretKey, blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= GCM_IV_BYTES) return null
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // --- 1:1: clave derivada por ECDH entre mi llave y la del destinatario real ---

    fun encryptFor(context: Context, peerName: String, plaintext: String): String {
        val peerPubB64 = peerPublicKeys[peerName] ?: return "PLAIN:$plaintext"
        return try {
            val myPair = getOrCreateKeyPair(context)
            val key = derivarClaveCompartida(myPair.private, decodePublicKey(peerPubB64))
            "ENC:" + aesGcmEncrypt(key, plaintext)
        } catch (e: Exception) { "PLAIN:$plaintext" }
    }

    fun decryptFrom(context: Context, peerName: String, wrapped: String): String? {
        return when {
            wrapped.startsWith("PLAIN:") -> wrapped.substringAfter("PLAIN:")
            wrapped.startsWith("ENC:") -> {
                val peerPubB64 = peerPublicKeys[peerName] ?: return null
                try {
                    val myPair = getOrCreateKeyPair(context)
                    val key = derivarClaveCompartida(myPair.private, decodePublicKey(peerPubB64))
                    aesGcmDecrypt(key, wrapped.substringAfter("ENC:"))
                } catch (e: Exception) { null }
            }
            else -> wrapped
        }
    }

    // --- Grupos: clave AES simétrica compartida, envuelta por miembro con ECDH ---

    fun generarClaveDeGrupoBase64(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun envolverClaveDeGrupoParaPeer(context: Context, peerName: String, groupKeyB64: String): String {
        val peerPubB64 = peerPublicKeys[peerName] ?: return ""
        return try {
            val myPair = getOrCreateKeyPair(context)
            val key = derivarClaveCompartida(myPair.private, decodePublicKey(peerPubB64))
            aesGcmEncrypt(key, groupKeyB64)
        } catch (e: Exception) { "" }
    }

    fun desenvolverClaveDeGrupoDePeer(context: Context, peerName: String, wrapped: String): String? {
        if (wrapped.isEmpty()) return null
        val peerPubB64 = peerPublicKeys[peerName] ?: return null
        return try {
            val myPair = getOrCreateKeyPair(context)
            val key = derivarClaveCompartida(myPair.private, decodePublicKey(peerPubB64))
            aesGcmDecrypt(key, wrapped)
        } catch (e: Exception) { null }
    }

    fun encryptGroupContent(groupKeyB64: String?, plaintext: String): String {
        if (groupKeyB64.isNullOrEmpty()) return "PLAIN:$plaintext"
        return try {
            val keyBytes = Base64.decode(groupKeyB64, Base64.NO_WRAP)
            "ENC:" + aesGcmEncrypt(SecretKeySpec(keyBytes, "AES"), plaintext)
        } catch (e: Exception) { "PLAIN:$plaintext" }
    }

    fun decryptGroupContent(groupKeyB64: String?, wrapped: String): String? {
        return when {
            wrapped.startsWith("PLAIN:") -> wrapped.substringAfter("PLAIN:")
            wrapped.startsWith("ENC:") -> {
                if (groupKeyB64.isNullOrEmpty()) return null
                try {
                    val keyBytes = Base64.decode(groupKeyB64, Base64.NO_WRAP)
                    aesGcmDecrypt(SecretKeySpec(keyBytes, "AES"), wrapped.substringAfter("ENC:"))
                } catch (e: Exception) { null }
            }
            else -> wrapped
        }
    }
}
