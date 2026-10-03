// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.model

/** A breathing gas. Fractions are permille; nitrogen is the remainder. */
data class GasMix(
    val id: Long = UNSAVED_ID,
    val o2Permille: Int,
    val hePermille: Int,
) {
    val n2Permille: Int get() = 1_000 - o2Permille - hePermille
}

/** A cylinder on one dive. Pressures millibar, volume millilitres. */
data class Tank(
    val id: Long = UNSAVED_ID,
    val diveId: Long,
    val index: Int,
    val volumeMl: Int? = null,
    val workingPressureMbar: Int? = null,
    val startPressureMbar: Int? = null,
    val endPressureMbar: Int? = null,
    val gasMixId: Long? = null,
)
