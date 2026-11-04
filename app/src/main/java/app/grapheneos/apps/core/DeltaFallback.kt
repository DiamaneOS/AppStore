// SPDX-License-Identifier: Apache-2.0
package app.grapheneos.apps.core

import java.net.ProtocolException
import java.security.GeneralSecurityException

// DiamaneOS: a delta the server cannot give, one that fails the catalog's hash, or one that does not
// patch to the right APK must not block the update.
class DeltaHashException(message: String) : GeneralSecurityException(message)
class DeltaPatchException(cause: Exception) : GeneralSecurityException(cause)

// A failure that a retry on the network could cure keeps the partial delta for the retry.
inline fun withDeltaFallback(useDelta: Boolean, attempt: (delta: Boolean) -> Unit, onFallback: (Exception) -> Unit) {
    if (useDelta) {
        try {
            attempt(true)
            return
        } catch (e: ProtocolException) {
            onFallback(e)
        } catch (e: DeltaHashException) {
            onFallback(e)
        } catch (e: DeltaPatchException) {
            onFallback(e)
        }
    }
    attempt(false)
}
