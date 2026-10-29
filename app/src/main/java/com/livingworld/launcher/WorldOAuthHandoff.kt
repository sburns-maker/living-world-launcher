package com.livingworld.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Browser OAuth return using ECDH P-256 + HKDF SHA-256 + AES-GCM.
 * Android's private key never leaves Android Keystore.
 * Only ciphertext appears in the external livingworld://auth link.
 */
object WorldOAuthHandoff {
  private const val ALIAS = "living_world_oauth_ecdh_v1"
  private const val PREFS = "living_world_oauth_pending"
  private const val MAX_AGE = 10 * 60 * 1000L
  private val INFO = "living-world-android-auth-v1".toByteArray(StandardCharsets.UTF_8)
  private val rnd = SecureRandom()

  // An in-memory handoff, never stored as plaintext or in a URL.
  data class Session(val access: String, val refresh: String)
  @Volatile private var awaitingSession: Session? = null
  @Volatile private var lastFailure: String? = null

  private fun encoder(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  private fun decoder(text: String): ByteArray =
    Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  private fun preferences(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun begin(context: Context): Uri {
    check(Build.VERSION.SDK_INT >= 31) {
      "Secure Google sign-in requires Android 12 or newer on this test build."
    }
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // Reject an old key from a previous flow; each login has a fresh private key.
    if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
    generator.initialize(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .build()
    )
    val pair = generator.generateKeyPair()
    val state = ByteArray(32).also(rnd::nextBytes)
    check(preferences(context).edit()
      .putString("state", encoder(state))
      .putLong("created", System.currentTimeMillis())
      .commit()) { "Unable to save Google sign-in request" }
    val url = Uri.parse(WorldBackend.ORIGIN + "/android-connect")
      .buildUpon()
      .appendQueryParameter("pub", encoder(pair.public.encoded))
      .appendQueryParameter("state", encoder(state))
      .build()
    return url
  }

  /** Browser callback. Invalid, expired and replayed requests never yield tokens. */
  fun accept(context: Context, uri: Uri): Session {
    require(uri.scheme == "livingworld" && uri.host == "auth") { "Invalid login return" }
    val ciphertext = uri.getQueryParameter("data")
      ?.takeIf { it.length in 64..20000 } ?: error("Missing encrypted sign-in data")
    val data = JSONObject(String(decoder(ciphertext), StandardCharsets.UTF_8))
    require(data.optInt("version") == 1) { "Unsupported sign-in response" }
    val stored = preferences(context)
    val expected = stored.getString("state", null) ?: error("No pending Google sign-in")
    val created = stored.getLong("created", 0L)
    val now = System.currentTimeMillis()
    require(now >= created && now - created < MAX_AGE) { "Google sign-in has expired" }
    val stateBytes = decoder(expected)
    require(stateBytes.size == 32) { "Invalid pending sign-in state" }

    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = store.getKey(ALIAS, null) as? java.security.PrivateKey
      ?: error("Sign-in encryption key is missing; please try again")
    val pubData = decoder(data.getString("pub"))
    require(pubData.size in 60..120) { "Invalid browser public key" }
    val publicKey = KeyFactory.getInstance("EC")
      .generatePublic(X509EncodedKeySpec(pubData))
    val agreement = KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
    agreement.init(key)
    agreement.doPhase(publicKey, true)
    val shared = agreement.generateSecret()

    // HKDF extract/expand; matches WebCrypto deriveKey on the trusted browser page.
    val hmac = Mac.getInstance("HmacSHA256")
    hmac.init(SecretKeySpec(stateBytes, "HmacSHA256"))
    val prk = hmac.doFinal(shared)
    hmac.init(SecretKeySpec(prk, "HmacSHA256"))
    val derived = hmac.doFinal(INFO + byteArrayOf(1)).copyOf(32)
    val iv = decoder(data.getString("iv"))
    require(iv.size == 12) { "Invalid sign-in nonce" }
    val payload = decoder(data.getString("payload"))
    require(payload.size in 32..12000) { "Invalid sign-in payload" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
    val message = JSONObject(String(cipher.doFinal(payload), StandardCharsets.UTF_8))
    val got = message.getString("state")
    require(MessageDigest.isEqual(stateBytes, decoder(got))) { "Sign-in state mismatch" }
    val issued = message.getLong("issuedAt")
    require(kotlin.math.abs(now - issued) < 3 * 60 * 1000L) { "Sign-in response expired" }
    val access = message.getString("access_token")
    val refresh = message.getString("refresh_token")
    // OAuth providers may issue opaque refresh tokens of different lengths.
    // Length is not a security property: session validity is determined by
    // the backend after setSession()/getUser(), not by this arbitrary minimum.
    require(access.isNotBlank() && access.length <= 12000) {
      "Google return did not contain an access token; please reconnect"
    }
    require(refresh.isNotBlank() && refresh.length <= 12000) {
      "Google return did not contain a refresh token; please reconnect"
    }
    // Consume pending state before navigation; a callback cannot be replayed.
    check(stored.edit().clear().commit()) { "Failed to complete the sign-in request" }
    store.deleteEntry(ALIAS)
    return Session(access, refresh)
  }

  fun publish(session: Session) {
    lastFailure = null
    awaitingSession = session
  }
  fun publishFailure(reason: String) {
    // Never persist or display browser-provided credentials or callback data.
    lastFailure = when {
      reason.contains("refresh token", true) -> "Google returned no refresh token. Please reconnect."
      reason.contains("access token", true) -> "Google returned no access token. Please reconnect."
      reason.contains("expired", true) -> "Google sign-in expired. Tap Connect with Google again."
      reason.contains("No pending", true) -> "Google sign-in request was lost. Tap Connect with Google again."
      reason.contains("state", true) -> "Google sign-in verification failed. Start a new connection."
      reason.contains("encryption", true) || reason.contains("decrypt", true) ->
        "Secure Google return could not be decrypted. Start a new connection."
      else -> "Google return could not be completed. Please try connecting again."
    }
  }
  fun takeFailure(): String? = synchronized(this) {
    val value = lastFailure
    lastFailure = null
    value
  }
  fun take(): Session? = synchronized(this) {
    val value = awaitingSession
    awaitingSession = null
    value
  }
  fun pending(): Boolean = awaitingSession != null
}

