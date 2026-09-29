package io.github.besliky.airplaytv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The receiver's long-term identity: a random device id (advertised like a MAC
 * address), a public UUID and the Ed25519 seed used for pairing.
 *
 * The seed is encrypted with an AES key held by the Android Keystore, so the private
 * key never sits in plain text in the app's storage. Devices with a broken Keystore
 * fall back to app-private storage.
 */
class Identity private constructor(
    val deviceId: ByteArray,
    val publicId: String,
    private val seed: ByteArray,
) {
    fun seedCopy(): ByteArray = seed.copyOf()

    val deviceIdHex: String get() = deviceId.joinToString("") { "%02X".format(it) }

    companion object {
        private const val PREFS = "identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_ID = "public_id"
        private const val KEY_SEED = "seed"
        private const val KEY_SEED_PLAIN = "seed_plain"
        private const val KEYSTORE_ALIAS = "airplaytv.identity"
        private const val GCM_TAG_BITS = 128

        private val random = SecureRandom()

        @Synchronized
        fun load(context: Context): Identity {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            val deviceId = prefs.getString(KEY_DEVICE_ID, null)?.let(::decodeHex)?.takeIf { it.size == 6 }
                ?: ByteArray(6).also {
                    random.nextBytes(it)
                    // locally administered, unicast
                    it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte()
                    editor.putString(KEY_DEVICE_ID, encodeHex(it))
                }

            val publicId = prefs.getString(KEY_PUBLIC_ID, null)
                ?: UUID.randomUUID().toString().also { editor.putString(KEY_PUBLIC_ID, it) }

            var seed = prefs.getString(KEY_SEED, null)?.let(::decryptSeed)
                ?: prefs.getString(KEY_SEED_PLAIN, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            if (seed == null || seed.size != 32) {
                seed = ByteArray(32).also(random::nextBytes)
                storeSeed(editor, seed)
            }
            editor.apply()
            return Identity(deviceId, publicId, seed)
        }

        /** Creates a new pairing identity. Every sender has to pair again afterwards. */
        @Synchronized
        fun resetPairingKey(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            storeSeed(editor, ByteArray(32).also(random::nextBytes))
            editor.apply()
        }

        private fun storeSeed(editor: android.content.SharedPreferences.Editor, seed: ByteArray) {
            val encrypted = encryptSeed(seed)
            if (encrypted != null) {
                editor.putString(KEY_SEED, encrypted).remove(KEY_SEED_PLAIN)
            } else {
                Log.w(Log.Category.PAIRING, "Keystore unavailable, storing the identity in app storage")
                editor.putString(KEY_SEED_PLAIN, Base64.encodeToString(seed, Base64.NO_WRAP)).remove(KEY_SEED)
            }
        }

        private fun keystoreKey(): SecretKey? = try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                }
                .generateKey()
        } catch (e: Exception) {
            Log.w(Log.Category.PAIRING, "Keystore key unavailable", e)
            null
        }

        private fun encryptSeed(seed: ByteArray): String? = try {
            val key = keystoreKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val out = cipher.iv + cipher.doFinal(seed)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(Log.Category.PAIRING, "identity encryption failed", e)
            null
        }

        private fun decryptSeed(stored: String): ByteArray? = try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val key = keystoreKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, data, 0, 12))
            cipher.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) {
            Log.w(Log.Category.PAIRING, "stored identity could not be decrypted, creating a new one", e)
            null
        }

        fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun decodeHex(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return try {
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
