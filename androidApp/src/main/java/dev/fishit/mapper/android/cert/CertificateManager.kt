package dev.fishit.mapper.android.cert

import android.content.Context
import android.security.KeyChain
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Manages SSL/TLS certificates for HTTPS MITM proxy.
 * Generates CA certificate and server certificates for intercepting HTTPS traffic.
 */
class CertificateManager(private val context: Context) {

    companion object {
        private const val TAG = "CertificateManager"
        private const val CA_ALIAS = "fishit-mapper-ca"
        private const val KEYSTORE_FILE = "fishit_ca.p12"
        private const val KEYSTORE_PASSWORD = "fishit-mapper-2026"
        private const val CERT_VALIDITY_DAYS = 365L
        
        init {
            // Registriere BouncyCastle Provider
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val keystoreFile: File by lazy {
        File(context.filesDir, KEYSTORE_FILE)
    }

    /**
     * Generiert ein neues CA-Zertifikat oder lädt ein bestehendes.
     */
    fun getOrCreateCACertificate(): Pair<X509Certificate, PrivateKey> {
        return if (keystoreFile.exists()) {
            loadCACertificate()
        } else {
            generateAndStoreCACertificate()
        }
    }

    /**
     * Generiert ein neues CA-Zertifikat und speichert es im Keystore.
     */
    private fun generateAndStoreCACertificate(): Pair<X509Certificate, PrivateKey> {
        Log.i(TAG, "Generating new CA certificate...")
        
        // Generiere Key Pair
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Erstelle CA-Zertifikat
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000)
        
        val issuer = X500Name("CN=FishIT-Mapper CA,O=FishIT-Mapper,C=DE")
        val subject = issuer
        val serialNumber = BigInteger.valueOf(now)
        
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        
        val certBuilder = X509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            publicKeyInfo
        )
        
        // CA Constraint Extension hinzufügen
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true)
        )
        
        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        
        val certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))
        
        // Speichere im Keystore
        saveCertificateToKeystore(certificate, keyPair.private)
        
        Log.i(TAG, "CA certificate generated successfully")
        return Pair(certificate, keyPair.private)
    }

    /**
     * Lädt das CA-Zertifikat aus dem Keystore.
     */
    private fun loadCACertificate(): Pair<X509Certificate, PrivateKey> {
        Log.i(TAG, "Loading existing CA certificate...")
        
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(keystoreFile).use { fis ->
            keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray())
        }
        
        val certificate = keyStore.getCertificate(CA_ALIAS) as X509Certificate
        val privateKey = keyStore.getKey(CA_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        
        Log.i(TAG, "CA certificate loaded successfully")
        return Pair(certificate, privateKey)
    }

    /**
     * Speichert das Zertifikat im internen Keystore.
     */
    private fun saveCertificateToKeystore(certificate: X509Certificate, privateKey: PrivateKey) {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        
        keyStore.setKeyEntry(
            CA_ALIAS,
            privateKey,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf<Certificate>(certificate)
        )
        
        FileOutputStream(keystoreFile).use { fos ->
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }
    }

    /**
     * Exportiert das CA-Zertifikat als PEM-Datei zur Installation.
     */
    fun exportCACertificate(outputFile: File): Boolean {
        return try {
            val (certificate, _) = getOrCreateCACertificate()
            
            val pemContent = buildString {
                append("-----BEGIN CERTIFICATE-----\n")
                append(android.util.Base64.encodeToString(
                    certificate.encoded,
                    android.util.Base64.NO_WRAP
                ).chunked(64).joinToString("\n"))
                append("\n-----END CERTIFICATE-----\n")
            }
            
            outputFile.writeText(pemContent)
            Log.i(TAG, "CA certificate exported to: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CA certificate", e)
            false
        }
    }

    /**
     * Generiert ein Server-Zertifikat für eine bestimmte Domain.
     * Wird vom MITM-Proxy verwendet, um sich als Server auszugeben.
     */
    fun generateServerCertificate(
        domain: String,
        caCertificate: X509Certificate,
        caPrivateKey: PrivateKey
    ): Pair<X509Certificate, PrivateKey> {
        // Generiere Key Pair für Server
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Erstelle Server-Zertifikat
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + 24 * 60 * 60 * 1000) // 1 Tag Gültigkeit
        
        val issuer = X500Name(caCertificate.subjectX500Principal.name)
        val subject = X500Name("CN=$domain,O=FishIT-Mapper,C=DE")
        val serialNumber = BigInteger.valueOf(now)
        
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        
        val certBuilder = X509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            publicKeyInfo
        )
        
        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(caPrivateKey)
        
        val certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))
        
        return Pair(certificate, keyPair.private)
    }

    /**
     * Prüft, ob ein CA-Zertifikat bereits existiert.
     */
    fun hasCACertificate(): Boolean {
        return keystoreFile.exists()
    }

    /**
     * Löscht das CA-Zertifikat (zum Zurücksetzen).
     */
    fun deleteCACertificate(): Boolean {
        return try {
            if (keystoreFile.exists()) {
                keystoreFile.delete()
                Log.i(TAG, "CA certificate deleted")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete CA certificate", e)
            false
        }
    }

    /**
     * Gibt Informationen über das CA-Zertifikat zurück.
     */
    fun getCACertificateInfo(): CertificateInfo? {
        return try {
            val (certificate, _) = getOrCreateCACertificate()
            val isInstalled = isCACertificateInstalledInSystem()
            CertificateInfo(
                subject = certificate.subjectX500Principal.name,
                issuer = certificate.issuerX500Principal.name,
                notBefore = certificate.notBefore,
                notAfter = certificate.notAfter,
                serialNumber = certificate.serialNumber.toString(),
                isInstalledInSystem = isInstalled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CA certificate info", e)
            null
        }
    }

    /**
     * Prüft, ob das CA-Zertifikat im Android-System installiert ist.
     * Verwendet TrustManager um zu prüfen, ob das Zertifikat vertraut wird.
     */
    fun isCACertificateInstalledInSystem(): Boolean {
        return try {
            if (!hasCACertificate()) {
                return false
            }
            
            val (certificate, _) = getOrCreateCACertificate()
            
            // Versuche das Zertifikat mit dem System-TrustManager zu verifizieren
            val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null) // Verwendet System-Keystore
            
            val trustManagers = trustManagerFactory.trustManagers
            for (trustManager in trustManagers) {
                if (trustManager is javax.net.ssl.X509TrustManager) {
                    val acceptedIssuers = trustManager.acceptedIssuers
                    // Prüfe, ob unser CA-Zertifikat in den akzeptierten Zertifikaten ist
                    if (acceptedIssuers.any { it.equals(certificate) }) {
                        Log.i(TAG, "CA certificate is installed in system")
                        return true
                    }
                }
            }
            
            Log.i(TAG, "CA certificate is NOT installed in system")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if CA certificate is installed in system", e)
            false
        }
    }
}

/**
 * Informationen über ein Zertifikat.
 */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notBefore: Date,
    val notAfter: Date,
    val serialNumber: String,
    val isInstalledInSystem: Boolean = false
)
