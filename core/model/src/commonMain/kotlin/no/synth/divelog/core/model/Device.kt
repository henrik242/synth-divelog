// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.model

/** A physical dive computer the user has downloaded from. */
data class Device(
    val id: Long = UNSAVED_ID,
    val vendor: String,
    val model: String,
    val serial: String? = null,
    val firmware: String? = null,
    val nickname: String? = null,
    val bluetoothAddress: String? = null,
)
