package org.jellyfin.androidtv.iroh

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IrohPairingInputTests : FunSpec({
	test("full invitation URLs keep using the SDK pairing path") {
		IrohTransport.isPairingInput("kausway://invite/example") shouldBe true
		IrohTransport.isPairingInput("  kausway://invite/example  ") shouldBe true
	}

	test("formatted compact lowercase and spaced codes use the SDK pairing path") {
		listOf(
			"K1-012345-6789AB-CDEFGH",
			"  k1-012345-6789ab-cdefgh  ",
			"K10123456789ABCDEFGH",
			"K1 012345 6789AB CDEFGH",
			"K 1 012345 6789AB CDEFGH",
			"K1-OIL345-6789AB-CDEFGH",
		).forEach { IrohTransport.isPairingInput(it) shouldBe true }
	}

	test("mistyped and incomplete codes reach SDK validation instead of HTTP discovery") {
		listOf("K1", "K1-", "k10123456789", "k1-short", "K1 incomplete", "K1-012345-6789AB-CDEFGU", "K1-" + "A".repeat(100))
			.forEach { IrohTransport.isPairingInput(it) shouldBe true }
	}

	test("normal server URLs and saved pairing are not new pairing inputs") {
		listOf(
			"http://server.example:8096",
			"https://K1-server.example:8920/jellyfin",
			"https://server.example/kausway://invite/example",
			"https://server.example/K1-012345-6789AB-CDEFGH",
			"server.example:8096",
			IrohTransport.ADDRESS,
			"jellyfin-iroh://invite/example",
			"",
		).forEach { IrohTransport.isPairingInput(it) shouldBe false }
	}
})
