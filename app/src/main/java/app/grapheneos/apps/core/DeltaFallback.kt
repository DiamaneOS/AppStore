// SPDX-License-Identifier: Apache-2.0
package app.grapheneos.apps.core

import java.net.ProtocolException
import java.security.GeneralSecurityException

// DiamaneOS: a delta the server cannot give, or one that fails the catalog's hash, must not block the update.
class DeltaHashException(message: String) : GeneralSecurityException(message)

// A failure that a retry on the network could cure, and a failed patch, are not for this to decide.
inline fun withDeltaFallback(useDelta: Boolean, attempt: (delta: Boolean) -> Unit, onFallback: (Exception) -> Unit) {
    if (useDelta) {
        try {
            attempt(true)
            return
        } catch (e: ProtocolException) {
            onFallback(e)
        } catch (e: DeltaHashException) {
            onFallback(e)
        }
    }
    attempt(false)
}
