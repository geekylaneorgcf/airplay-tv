package io.github.besliky.airplaytv.lg

import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The trust rules are the security of the TV connection, so they are tested with two throwaway certificates. */
class TvTrustTest {

    // two self-signed certificates made for these tests (no private key is kept anywhere)
    private val a: X509Certificate = TvTrust.decode("MIIDGzCCAgOgAwIBAgIUAaVdkXK3XRypazMWIYoHfQ3glI0wDQYJKoZIhvcNAQELBQAwHDEaMBgGA1UEAwwRQWlyUGxheSBUViB0ZXN0IGEwIBcNMjYxMDAxMTM1MTM4WhgPMjEyNjA5MDcxMzUxMzhaMBwxGjAYBgNVBAMMEUFpclBsYXkgVFYgdGVzdCBhMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqlb2G/C8XFGIG/0/w+2LWvHPZ3S+AF4GnRcaO9tLx3xzVQAONtAhekdFrAPkHTJa+5MY6QdFWCuI3qL+cl4OrfC44wUzjGgEHheq+3kYyVC+cI0q+u9kwN0dV4KnYHltp7dGx8BrYmgIJ8dUFkl2pLxTRBUdsbaCzx6vO7cHonIiBGO5N+7VeFji+90hwZnL9VSOIIQvREk7OGT8bGe0s2astngua2zNMJX40Gf/0VUeXqDP1eb4TOiBQ2ILmeM9jDfWyg+VyyOI7Qa4VlqMDCyMVpArEUSAUQkIaOMRSfOaJN2otGEMqEgmMPbOZ/l+IfFTyizVt2aO4Sw7Lif06QIDAQABo1MwUTAdBgNVHQ4EFgQUcvJoUCJJPCoFdUC+tSNbYzUMURcwHwYDVR0jBBgwFoAUcvJoUCJJPCoFdUC+tSNbYzUMURcwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAqPaSv6SLCzqYkAHKmKQHI3OzmaOJrP83BQ74rK3KV2/2v1NQA680h6L8CEWxUdd3tiCkwiAOJwwxL/iKAQ4vYR7WG6budTLSsqs9bt+kz3ix/HIler+2A0V8nFabCNfU9z+FTnRGqOduJ6jCfk/BUi8uU/uEdWrc0c4s9oT43quYs7gHuvQm1z4cIclhJubBik69v12IoS6FjL9Qv/vX3v9y5gNgHHHY4JzSr+ay0R1VmhFwNrdL09AzOpLOZZHg/xAyjzR58Fz79zV75TDJBUi+V/pfUYLzg1x0F5vyQqNJcQh7UhYBmEOejCst3nWUsvjVHrwfCJio817N+6EgAQ==")!!
    private val b: X509Certificate = TvTrust.decode("MIIDGzCCAgOgAwIBAgIUUhFfQelahvx4churA8xxNRZTG0kwDQYJKoZIhvcNAQELBQAwHDEaMBgGA1UEAwwRQWlyUGxheSBUViB0ZXN0IGIwIBcNMjYxMDAxMTM1MTM4WhgPMjEyNjA5MDcxMzUxMzhaMBwxGjAYBgNVBAMMEUFpclBsYXkgVFYgdGVzdCBiMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3bGsN1ocLbs8m08H91q+idMjLBPQXdp7kxT3+JizA1r3eJLGpgbKsb5urWQNXbeVU729Cc0GcZZ6d6EfmWUsDyVXkkMRfUEgVXd7I+KMffR+tYPWDjPYUbS0rdac0ZiPoUhdwmKnLVLua3nlTAbt+2oPKCacV+usu7JrxPTmxQWqg7Mr68jq8Jix7BGEp5KZ58/F4gVl+mGkKlBJ1vu/fAZFnbN5BaYnUo0JNDyEwD221CGj7RHCleuCUbVuKFqn75WYuxnRqSwVpbEviSJILy9ftr059BozHUgoTQLe8UeLXdPGYRJWuDOEhMx0MXb0wZ1P7XjeXYrK3O5zhM7oCwIDAQABo1MwUTAdBgNVHQ4EFgQUNClMTHuM7j4AE59d/mQ0SFMpiwIwHwYDVR0jBBgwFoAUNClMTHuM7j4AE59d/mQ0SFMpiwIwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAVXHhc5C9rOlKawFrRiHICc5s1ygHnIz256gMC8G4PQgz9kjvJCp7m/pMUFoWemA/vDihfg0B0sCI6Gn/lSq3iaLtARQjTh8tZ/bJVnr/Z4DdbpCul+WigKaz5QaWadXR3owVsZV6lAFeGdmKaMWktHZ8AtnhnLqRx0Es1VzxEjsSA0Ozi1fmPpWNeZAit3OT8EfrS4POrHOnIbIsVkJLVNdoGuNh0dwUtvmOuL+8yoBRIVkO3IAb5EuUpTLHNC98RPksonuBw/Hz54oAk63DXUKcEgT1E+w5CirABHFH1h4emYfqu/ecyVzcyLnsHr+JHq2qFrCqFb27d7qVTDhsxw==")!!

    private fun rejects(trust: TvTrust, cert: X509Certificate): Boolean = try {
        trust.trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        false
    } catch (_: CertificateException) {
        true
    }

    @Test
    fun `a certificate round-trips through its stored form`() {
        assertEquals(a, TvTrust.decode(TvTrust.encode(a)))
    }

    @Test
    fun `what is not a certificate decodes to nothing`() {
        assertNull(TvTrust.decode(null))
        assertNull(TvTrust.decode(""))
        assertNull(TvTrust.decode("not base64 at all"))
        assertNull(TvTrust.decode("AAAA"))
    }

    @Test
    fun `the fingerprint is the SHA-256 of the certificate in colon-separated hex`() {
        assertEquals("46:8E:84:EE:5B:DE:D0:12:07:01:73:2A:A5:97:AA:B7:9F:7F:B2:D3:2E:DE:56:C4:93:43:FD:4D:C1:19:ED:52", TvTrust.fingerprint(a))
    }

    @Test
    fun `without a pin a certificate no authority signed is refused`() {
        assertTrue(rejects(TvTrust(null), a))
        assertTrue(rejects(TvTrust(null), b))
    }

    @Test
    fun `with a pin exactly that certificate is trusted and no other`() {
        val trust = TvTrust(a)
        trust.trustManager.checkServerTrusted(arrayOf(a), "RSA") // does not throw
        assertTrue("another certificate must not be trusted", rejects(trust, b))
    }

    @Test
    fun `a refused certificate is reported with the certificate the TV showed`() {
        val trust = TvTrust(null)
        assertTrue(rejects(trust, a))
        val explained = trust.explain(SSLHandshakeException("not trusted"))
        assertTrue(explained is TvTrust.Untrusted)
        assertEquals(a, (explained as TvTrust.Untrusted).certificate)
        assertEquals(TvTrust.fingerprint(a), explained.fingerprint)
    }

    @Test
    fun `other failures are passed on unchanged`() {
        val trust = TvTrust(null)
        assertTrue(rejects(trust, a))
        val refused = IOException("connection refused")
        assertEquals(refused, trust.explain(refused))
        assertNotNull(trust.explain(SSLHandshakeException("x")))
    }

    @Test
    fun `a failure with no certificate shown is not blamed on one`() {
        val error = SSLHandshakeException("handshake failed")
        assertEquals(error, TvTrust(null).explain(error))
    }

    @Test
    fun `trusting nothing never accepts, even for the pinned one when it is shown first`() {
        try {
            TvTrust(null).trustManager.checkServerTrusted(arrayOf(a), "RSA")
            fail("a self-signed certificate must not be trusted without a pin")
        } catch (_: CertificateException) {
            // expected
        }
    }
}
