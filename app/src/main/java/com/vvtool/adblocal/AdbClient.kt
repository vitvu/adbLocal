package com.vvtool.adblocal

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * ADB client used to talk to this phone's own adbd over Wireless debugging.
 * The RSA key + self-signed certificate are generated once and kept in app storage;
 * pairing trusts that key permanently, so later connections need no user action.
 */
class AdbClient private constructor(
    private val key: PrivateKey,
    private val cert: Certificate,
) : AbsAdbConnectionManager() {

    init {
        api = Build.VERSION.SDK_INT
    }

    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "adblocal"

    companion object {
        private val lock = Any()

        /** A fresh client each time: a manager instance can only connect once. */
        fun create(ctx: Context): AdbClient = synchronized(lock) {
            val dir = File(ctx.filesDir, "adbkey").apply { mkdirs() }
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            if (!keyFile.exists() || !certFile.exists()) generate(keyFile, certFile)
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
            AdbClient(key, cert)
        }

        private fun generate(keyFile: File, certFile: File) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val alg = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=adblocal")
            val now = System.currentTimeMillis()
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger.valueOf(now)))
                setSignature(alg)
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(Date(now - 86_400_000L)))
                setEndDate(Time(Date(now + 30L * 365 * 86_400_000L)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.public.encoded))
            }.generateTBSCertificate()
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(kp.private)
                update(tbs.encoded)
                sign()
            }
            val certDer = DERSequence(ASN1EncodableVector().apply {
                add(tbs); add(alg); add(DERBitString(sig))
            }).encoded
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(certDer)
        }
    }
}
