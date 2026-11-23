package com.kompi.app.assistant.push

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test-only RFC 8291 sender used to exercise the released Connector SDK decrypt/IPC path. */
internal object WebPushTestEncryptor {
    private val encoder = StandardCharsets.UTF_8

    fun encrypt(cleartext: ByteArray, p256dh: String, auth: String): ByteArray {
        val recipientPublicRaw = decode(p256dh)
        require(recipientPublicRaw.size == 65 && recipientPublicRaw[0] == 0x04.toByte())
        val recipientPublic = publicKey(recipientPublicRaw)
        val authSecret = decode(auth)
        require(authSecret.size == 16)

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val sender = generator.generateKeyPair()
        val senderPublicRaw = publicPoint(sender.public as java.security.interfaces.ECPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(sender.private)
        agreement.doPhase(recipientPublic, true)
        val sharedSecret = agreement.generateSecret()

        val keyPrk = hkdfExtract(authSecret, sharedSecret)
        val keyInfo = concat("WebPush: info\u0000".toByteArray(encoder), recipientPublicRaw, senderPublicRaw)
        val inputKeyMaterial = hkdfExpand(keyPrk, keyInfo, 32)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val prk = hkdfExtract(salt, inputKeyMaterial)
        val cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(encoder), 16)
        val nonce = hkdfExpand(prk, "Content-Encoding: nonce\u0000".toByteArray(encoder), 12)
        val plaintext = concat(cleartext, byteArrayOf(0x02))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArrayOutputStream().apply {
            write(salt)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(4096).array())
            write(byteArrayOf(senderPublicRaw.size.toByte()))
            write(senderPublicRaw)
            write(ciphertext)
        }.toByteArray()
    }

    private fun publicKey(raw: ByteArray): java.security.PublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
    }

    private fun publicPoint(key: java.security.interfaces.ECPublicKey): ByteArray = byteArrayOf(0x04) +
        key.w.affineX.toFixedWidth() + key.w.affineY.toFixedWidth()

    private fun BigInteger.toFixedWidth(): ByteArray {
        val value = toByteArray()
        return when {
            value.size == 32 -> value
            value.size == 33 && value[0] == 0.toByte() -> value.copyOfRange(1, value.size)
            value.size < 32 -> ByteArray(32 - value.size) + value
            else -> error("P-256 coordinate exceeds 32 bytes")
        }
    }

    private fun hkdfExtract(salt: ByteArray, input: ByteArray): ByteArray = hmac(salt, input)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(prk, concat(previous, info, byteArrayOf(counter.toByte())))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun concat(vararg arrays: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        arrays.forEach { write(it) }
    }.toByteArray()
}
