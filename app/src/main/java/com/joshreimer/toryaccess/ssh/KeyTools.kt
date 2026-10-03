package com.joshreimer.toryaccess.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.joshreimer.toryaccess.data.SecretBox
import com.joshreimer.toryaccess.data.SshKey
import java.io.ByteArrayOutputStream

enum class KeyKind(val label: String) { ED25519("Ed25519"), RSA("RSA 4096") }

class KeyImportException(message: String) : Exception(message)

object KeyTools {
    fun generate(kind: KeyKind, label: String): SshKey {
        val jsch = JSch()
        val kp = when (kind) {
            KeyKind.ED25519 -> KeyPair.genKeyPair(jsch, KeyPair.ED25519)
            KeyKind.RSA -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
        }
        try {
            val prv = ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
            return build(kp, label, prv, passphrase = null)
        } finally {
            kp.dispose()
        }
    }

    /** Accepts OpenSSH, PEM (PKCS#1/#8) or PuTTY private keys, optionally passphrase-protected. */
    fun import(privateKey: ByteArray, passphrase: String?, label: String): SshKey {
        val jsch = JSch()
        val kp = try {
            KeyPair.load(jsch, privateKey, null)
        } catch (e: Exception) {
            throw KeyImportException("Not a private key JSch understands: ${e.message}")
        }
        try {
            if (kp.isEncrypted) {
                if (passphrase.isNullOrEmpty()) throw KeyImportException("This key is encrypted — enter its passphrase")
                if (!kp.decrypt(passphrase)) throw KeyImportException("Wrong passphrase")
            }
            return build(kp, label, privateKey, passphrase?.takeIf { it.isNotEmpty() })
        } finally {
            kp.dispose()
        }
    }

    private fun build(kp: KeyPair, label: String, prv: ByteArray, passphrase: String?): SshKey {
        val comment = label.replace(Regex("\\s+"), "-")
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString().trim()
        val blob = kp.publicKeyBlob ?: throw KeyImportException("Couldn't derive the public key")
        return SshKey(
            label = label,
            type = kp.keyTypeString,
            publicKey = pub,
            fingerprint = sha256Fingerprint(blob),
            privateKey = SecretBox.seal(prv),
            passphrase = passphrase?.let { SecretBox.seal(it) },
        )
    }
}
