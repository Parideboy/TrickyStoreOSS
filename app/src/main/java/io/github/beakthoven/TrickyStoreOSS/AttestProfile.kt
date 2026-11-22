/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import java.io.File

/**
 * Identity that a forged attestation record should carry.
 *
 * Why this exists: keystore2 fills the ATTESTATION_ID_* parameters from the real system
 * properties, not from the requesting app, so a Build-level spoof (PIF/zygisk) never
 * reaches the attestation path. The record then attests the physical device while the
 * app tells Play something else, and the verdict is capped regardless of how good the
 * keybox is.
 *
 * This object reads whichever PIF profile is currently active (the most recently
 * written of the known profile files) so the forged identity always follows the
 * chosen fingerprint instead of being hard-coded to one device.
 */
object AttestProfile {
    private const val CONFIG_DIR = "/data/adb/tricky_store"

    private val candidates =
        listOf(
            "$CONFIG_DIR/custom.pif.prop",
            "/data/adb/modules/tricky_store/pif.prop",
            "$CONFIG_DIR/pif.prop",
        )

    data class Profile(
        val source: String,
        val brand: String? = null,
        val device: String? = null,
        val product: String? = null,
        val manufacturer: String? = null,
        val model: String? = null,
        /** KeyMint OS_VERSION (SDK * 10000), e.g. Android 15 -> 150000. */
        val osVersion: Int? = null,
        /** KeyMint OS_PATCHLEVEL in YYYYMM form. */
        val osPatchLevel: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = brand == null && device == null && product == null && manufacturer == null && model == null
    }

    @Volatile private var cached: Profile? = null
    @Volatile private var cachedStamp: Long = -1

    fun current(): Profile? {
        val newest =
            candidates.map { File(it) }.filter { it.isFile }.maxByOrNull { it.lastModified() }
                ?: return null
        val stamp = newest.lastModified() xor newest.length()
        cached?.let { if (stamp == cachedStamp) return it.takeUnless { p -> p.isEmpty } }
        val parsed =
            runCatching { parse(newest) }.getOrElse {
                Logger.w("attest profile: cannot parse ${newest.path}: ${it.message}")
                return cached
            }
        cached = parsed
        cachedStamp = stamp
        if (parsed.isEmpty) {
            Logger.w("attest profile: ${newest.path} has no identity fields; keeping hardware ids")
        } else {
            Logger.i(
                "attest profile ${newest.name}: brand=${parsed.brand} device=${parsed.device} " +
                    "product=${parsed.product} manufacturer=${parsed.manufacturer} model=${parsed.model} " +
                    "osVersion=${parsed.osVersion} patch=${parsed.osPatchLevel}"
            )
        }
        return parsed.takeUnless { it.isEmpty }
    }

    private fun parse(file: File): Profile {
        val values =
            file
                .readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .map { line ->
                    val k = line.substringBefore('=').trim().uppercase()
                    val v = line.substringAfter('=').trim().trim('"', '\'')
                    k to v
                }.toMap()

        // FINGERPRINT = brand/device/product:release/id/incremental:type/tags
        val fp = values["FINGERPRINT"]
        val fromFp =
            fp?.split(':')?.firstOrNull()?.split('/').orEmpty().let { parts ->
                listOfNotNull(
                    parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                    parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                )
            }

        val brand = values["BRAND"]?.takeIf { it.isNotBlank() } ?: fromFp.getOrNull(0)
        val device = values["DEVICE"]?.takeIf { it.isNotBlank() } ?: fromFp.getOrNull(1)
        val product = values["PRODUCT"]?.takeIf { it.isNotBlank() } ?: fromFp.getOrNull(2)
        val manufacturer = values["MANUFACTURER"]?.takeIf { it.isNotBlank() }
        val model = values["MODEL"]?.takeIf { it.isNotBlank() }

        val osVersion =
            (values["OS_VERSION"] ?: values["ATTEST_OS_VERSION"])
                ?.toIntOrNull()
                ?.takeIf { it in 10000..999999 }
        val osPatchLevel =
            (values["OS_PATCH_LEVEL"] ?: values["SECURITY_PATCH"])?.let { parsePatchLevel(it) }

        return Profile(
            source = file.path,
            brand = brand,
            device = device,
            product = product,
            manufacturer = manufacturer,
            model = model,
            osVersion = osVersion,
            osPatchLevel = osPatchLevel,
        )
    }

    /** Accepts 2026-11-05, 2026-11 and 202611; returns KeyMint YYYYMM. */
    private fun parsePatchLevel(raw: String): Int? {
        val digits = raw.filter { it.isDigit() }
        return when (digits.length) {
            8 -> digits.substring(0, 6).toIntOrNull()
            6 -> digits.toIntOrNull()
            4 -> digits.toIntOrNull()?.times(100)
            else -> null
        }
    }
}
