package com.androidtv.gameswidget.net

import com.androidtv.gameswidget.crypto.CryptoProvider
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayInputStream
import java.security.Key
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * NVIDIA GameStream PIN pairing handshake.
 * Direct port of moonlight-android's PairingManager.
 */
class PairingManager(
    private val http: GameStreamHttp,
    crypto: CryptoProvider,
) {
    enum class PairState { NOT_PAIRED, PAIRED, PIN_WRONG, FAILED, ALREADY_IN_PROGRESS }

    private val cert: X509Certificate = crypto.clientCertificate
    private val pemCertBytes: ByteArray = crypto.pemEncodedClientCertificate
    private val pk: PrivateKey = crypto.clientPrivateKey

    /** Server cert obtained during pairing; pin this for subsequent HTTPS. */
    var pairedCert: X509Certificate? = null
        private set

    fun generatePin(): String {
        val r = SecureRandom()
        return "%d%d%d%d".format(r.nextInt(10), r.nextInt(10), r.nextInt(10), r.nextInt(10))
    }

    /**
     * Runs the full handshake. The PC user must enter [pin] into the host's Web UI
     * while this is in progress (the first request has no read timeout for that reason).
     */
    fun pair(serverInfo: String, pin: String): PairState {
        val hash: HashAlgo = if (http.getComputerDetails(serverInfo).majorVersion >= 7) Sha256() else Sha1()

        val salt = randomBytes(16)
        val aesKey = generateAesKey(hash, saltPin(salt, pin))

        // 1. Send salt + our cert, receive the server's cert.
        val getCert = http.executePairingCommand(
            "phrase=getservercert&salt=${salt.toHex()}&clientcert=${pemCertBytes.toHex()}",
            enableReadTimeout = false,
        )
        if (GameStreamHttp.xmlString(getCert, "paired") != "1") return PairState.FAILED

        val srvCert = extractPlainCert(getCert) ?: run {
            http.unpair(); return PairState.ALREADY_IN_PROGRESS
        }
        pairedCert = srvCert
        http.serverCert = srvCert // pin for HTTPS calls below

        // 2. Encrypt a random challenge with the PIN-derived key.
        val randomChallenge = randomBytes(16)
        val encChallenge = encryptAes(randomChallenge, aesKey)
        val challengeResp = http.executePairingCommand("clientchallenge=${encChallenge.toHex()}", true)
        if (GameStreamHttp.xmlString(challengeResp, "paired") != "1") { http.unpair(); return PairState.FAILED }

        // 3. Decode server's response + its challenge.
        val decResp = decryptAes(GameStreamHttp.xmlString(challengeResp, "challengeresponse")!!.hexToBytes(), aesKey)
        val serverResponse = decResp.copyOfRange(0, hash.length)
        val serverChallenge = decResp.copyOfRange(hash.length, hash.length + 16)

        val clientSecret = randomBytes(16)
        val challengeRespHash = hash.hash(serverChallenge + cert.signature + clientSecret)
        val challengeRespEnc = encryptAes(challengeRespHash, aesKey)
        val secretResp = http.executePairingCommand("serverchallengeresp=${challengeRespEnc.toHex()}", true)
        if (GameStreamHttp.xmlString(secretResp, "paired") != "1") { http.unpair(); return PairState.FAILED }

        // 4. Verify the server's signed secret (MITM check).
        val serverSecretResp = GameStreamHttp.xmlString(secretResp, "pairingsecret")!!.hexToBytes()
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)
        if (!verifySignature(serverSecret, serverSignature, srvCert)) { http.unpair(); return PairState.FAILED }

        // 5. Verify the PIN was correct.
        val expected = hash.hash(randomChallenge + srvCert.signature + serverSecret)
        if (!expected.contentEquals(serverResponse)) { http.unpair(); return PairState.PIN_WRONG }

        // 6. Send our signed secret.
        val clientPairingSecret = clientSecret + signData(clientSecret, pk)
        val clientSecretResp = http.executePairingCommand("clientpairingsecret=${clientPairingSecret.toHex()}", true)
        if (GameStreamHttp.xmlString(clientSecretResp, "paired") != "1") { http.unpair(); return PairState.FAILED }

        // 7. Final challenge over HTTPS to confirm paired state.
        val pairChallenge = http.executePairingChallenge()
        if (GameStreamHttp.xmlString(pairChallenge, "paired") != "1") { http.unpair(); return PairState.FAILED }

        return PairState.PAIRED
    }

    // ---- crypto helpers ----------------------------------------------------

    private fun extractPlainCert(xml: String): X509Certificate? {
        val hex = GameStreamHttp.xmlString(xml, "plaincert") ?: return null
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(hex.hexToBytes())) as X509Certificate
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun saltPin(salt: ByteArray, pin: String) = salt + pin.toByteArray(Charsets.UTF_8)

    private fun generateAesKey(hash: HashAlgo, keyData: ByteArray) = hash.hash(keyData).copyOf(16)

    private fun performBlockCipher(c: BlockCipher, input: ByteArray): ByteArray {
        val blockSize = c.blockSize
        val rounded = (input.size + (blockSize - 1)) and (blockSize - 1).inv()
        val inBuf = input.copyOf(rounded)
        val outBuf = ByteArray(rounded)
        var off = 0
        while (off < rounded) { c.processBlock(inBuf, off, outBuf, off); off += blockSize }
        return outBuf
    }

    private fun encryptAes(data: ByteArray, key: ByteArray) =
        performBlockCipher(AESLightEngine().apply { init(true, KeyParameter(key)) }, data)

    private fun decryptAes(data: ByteArray, key: ByteArray) =
        performBlockCipher(AESLightEngine().apply { init(false, KeyParameter(key)) }, data)

    private fun signatureFor(key: Key): Signature = when (key.algorithm) {
        "RSA" -> Signature.getInstance("SHA256withRSA")
        "EC" -> Signature.getInstance("SHA256withECDSA")
        else -> throw NoSuchAlgorithmException("Unhandled key algorithm: ${key.algorithm}")
    }

    private fun verifySignature(data: ByteArray, signature: ByteArray, cert: Certificate): Boolean {
        val sig = signatureFor(cert.publicKey)
        sig.initVerify(cert.publicKey)
        sig.update(data)
        return sig.verify(signature)
    }

    private fun signData(data: ByteArray, key: PrivateKey): ByteArray {
        val sig = signatureFor(key)
        sig.initSign(key)
        sig.update(data)
        return sig.sign()
    }

    private interface HashAlgo { val length: Int; fun hash(d: ByteArray): ByteArray }
    private class Sha1 : HashAlgo {
        override val length = 20
        override fun hash(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(d)
    }
    private class Sha256 : HashAlgo {
        override val length = 32
        override fun hash(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)
    }
}

private val HEX = "0123456789ABCDEF".toCharArray()
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Illegal hex length: $length" }
    return ByteArray(length / 2) { ((Character.digit(this[it * 2], 16) shl 4) + Character.digit(this[it * 2 + 1], 16)).toByte() }
}

private class NoSuchAlgorithmException(msg: String) : java.security.NoSuchAlgorithmException(msg)
