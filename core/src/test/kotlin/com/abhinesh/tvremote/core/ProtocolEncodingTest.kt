package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.crypto.PairingSecret
import com.abhinesh.tvremote.core.crypto.SelfSignedCertificate
import com.abhinesh.tvremote.core.proto.Framing
import com.abhinesh.tvremote.core.proto.PoloMessages
import com.abhinesh.tvremote.core.proto.ProtoWriter
import com.abhinesh.tvremote.core.proto.RemoteMessages
import com.abhinesh.tvremote.core.proto.RemoteMessages.Direction
import com.abhinesh.tvremote.core.proto.RemoteMessages.Inbound
import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.TvCertificateInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Byte-level expectations derived by hand from the protobuf wire format and the .proto field numbers. */
class ProtocolEncodingTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun varints() {
        assertContentEquals(hex("00"), ProtoWriter.encodeVarint(0))
        assertContentEquals(hex("ee 04"), ProtoWriter.encodeVarint(622))
        assertContentEquals(hex("c8 01"), ProtoWriter.encodeVarint(200))
        assertContentEquals(hex("ff ff ff ff ff ff ff ff ff 01"), ProtoWriter.encodeVarint(-1))
    }

    @Test fun keyInjectMatchesWireFormat() {
        // RemoteMessage{ remote_key_inject(10){ key_code(1)=3 HOME, direction(2)=3 SHORT } }
        assertContentEquals(hex("52 04 08 03 10 03"), RemoteMessages.keyInject(KeyCodes.HOME, Direction.SHORT))
        assertContentEquals(hex("52 04 08 17 10 01"), RemoteMessages.keyInject(KeyCodes.DPAD_CENTER, Direction.START_LONG))
    }

    @Test fun setActiveAndPing() {
        assertContentEquals(hex("12 03 08 ee 04"), RemoteMessages.setActive(622))
        assertContentEquals(hex("4a 02 08 05"), RemoteMessages.pingResponse(5))
        assertContentEquals(hex("4a 00"), RemoteMessages.pingResponse(0)) // proto3 drops the zero, keeps the message
    }

    @Test fun configureResponseMatchesReference() {
        val expected = hex("0a 1c 08 ee 04 12 17 18 01 22 01 31 2a 09") + "atvremote".toByteArray() +
            hex("32 05") + "1.0.0".toByteArray()
        assertContentEquals(expected, RemoteMessages.configureResponse(622))
    }

    @Test fun appLinkAndVoice() {
        assertContentEquals(hex("d2 05 0c 0a 0a") + "netflix://".toByteArray(), RemoteMessages.appLinkLaunch("netflix://"))
        assertContentEquals(hex("f2 01 02 08 07"), RemoteMessages.voiceBegin(7))
        assertContentEquals(hex("82 02 02 08 07"), RemoteMessages.voiceEnd(7))
    }

    @Test fun poloMessages() {
        assertContentEquals(hex("08 02 10 c8 01 52 0e 0a 09") + "atvremote".toByteArray() + hex("12 01") + "x".toByteArray(),
            PoloMessages.pairingRequest("x"))
        assertContentEquals(hex("08 02 10 c8 01 a2 01 08 0a 04 08 03 10 06 18 01"), PoloMessages.options())
        assertContentEquals(hex("08 02 10 c8 01 f2 01 08 0a 04 08 03 10 06 10 01"), PoloMessages.configuration())
        val secret = ByteArray(32) { it.toByte() }
        assertContentEquals(hex("08 02 10 c8 01 c2 02 22 0a 20") + secret, PoloMessages.secret(secret))
        val ack = PoloMessages.parse(hex("08 02 10 c8 01 5a 00"))
        assertEquals(PoloMessages.Kind.PAIRING_REQUEST_ACK, ack.kind)
        assertEquals(200, ack.status)
        assertEquals(402, PoloMessages.parse(hex("08 02 10 92 03")).status)
    }

    @Test fun parsesTvMessages() {
        val configure = ProtoWriter().message(1) {
            int32(1, 623)
            message(2) { string(1, "MiTV-AXFR2"); string(2, "Xiaomi"); string(6, "7.00") }
        }.toByteArray()
        val c = assertIs<Inbound.Configure>(RemoteMessages.parse(configure))
        assertEquals(623, c.features)
        assertEquals("MiTV-AXFR2", c.model)
        assertEquals("Xiaomi", c.vendor)

        val vol = ProtoWriter().message(50) { varint(6, 100); varint(7, 17) }.toByteArray()
        assertEquals(Inbound.Volume(17, 100, false, ""), RemoteMessages.parse(vol))

        val app = ProtoWriter().message(20) { message(1) { string(12, "com.netflix.ninja") } }.toByteArray()
        assertEquals("com.netflix.ninja", assertIs<Inbound.ImeKeyInject>(RemoteMessages.parse(app)).appPackage)

        // What the MiTV-AXFR2 sent when it refused market://launch: remote_error echoing the request.
        val err = ProtoWriter().message(3) { bool(1, true); message(2) { message(90) { string(1, "market://launch?id=x") } } }.toByteArray()
        assertEquals("market://launch?id=x", assertIs<Inbound.Error>(RemoteMessages.parse(err)).rejectedAppLink)
        assertIs<Inbound.Start>(RemoteMessages.parse(hex("c2 02 02 08 01")))
    }

    @Test fun framingRoundTripAcrossMessages() {
        val out = ByteArrayOutputStream()
        val big = ByteArray(20_000) { 1 }
        Framing.write(out, RemoteMessages.setActive(622))
        Framing.write(out, big)
        val input = ByteArrayInputStream(out.toByteArray())
        assertContentEquals(RemoteMessages.setActive(622), Framing.read(input))
        assertContentEquals(big, Framing.read(input))
    }

    @Test fun pairingSecretByteHandling() {
        assertContentEquals(hex("01 00 01"), PairingSecret.unsignedBytes(BigInteger.valueOf(65537)))
        assertContentEquals(hex("80"), PairingSecret.unsignedBytes(BigInteger.valueOf(128))) // strips the sign byte
        assertTrue(PairingSecret.isWellFormed("D264E0"))
        assertTrue(!PairingSecret.isWellFormed("D264EZ"))
        assertTrue(!PairingSecret.isWellFormed("D264E"))
    }

    @Test fun selfSignedCertificateIsValid() {
        val id = ClientIdentity.generate("TVRemote test")
        id.certificate.checkValidity()
        id.certificate.verify(id.certificate.publicKey)
        assertEquals(3, id.certificate.version)
        assertEquals(0, id.certificate.basicConstraints) // CA with pathLen 0, like the reference cert
        assertEquals(2048, id.publicKey.modulus.bitLength())
        assertEquals("CN=TVRemote test", id.certificate.subjectX500Principal.name)
    }

    @Test fun parsesTvCertificateSubject() {
        val kp = SelfSignedCertificate.generateKeyPair()
        val cert = SelfSignedCertificate.create("atvremote/darcy/darcy/SHIELD Android TV/aa:bb:cc:dd:ee:ff", kp)
        val info = TvCertificateInfo.from(cert)
        assertEquals("SHIELD Android TV", info.model)
        assertEquals("AA:BB:CC:DD:EE:FF", info.macAddress)
    }

    @Test fun shortcutTargets() {
        assertEquals("market://launch?id=com.netflix.ninja", AppShortcut.toAppLink("com.netflix.ninja"))
        assertEquals("netflix://", AppShortcut.toAppLink("netflix://"))
        assertEquals("https://www.youtube.com", AppShortcut.toAppLink(" https://www.youtube.com "))
    }
}
