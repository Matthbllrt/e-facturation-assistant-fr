package com.radardeal.app.web

import com.radardeal.app.domain.model.ScanStatus

/**
 * Result of one attempt to read a Vinted catalogue page.
 *
 * Every failure mode Vinted can produce is a named value here rather than an exception, so the
 * scheduler and the UI can react to each one specifically (back off, ask for a login, show a
 * verification notice) instead of showing a generic error.
 */
sealed interface FetchOutcome {

    data class Success(val body: String, val via: Transport) : FetchOutcome

    /** No usable session — the user has to sign in to Vinted inside RadarDeal. */
    data object NeedsLogin : FetchOutcome

    /** Vinted served an anti-bot interstitial; only the user can clear it. */
    data object NeedsVerification : FetchOutcome

    /** HTTP 429 or equivalent. The scheduler slows this watch down. */
    data object RateLimited : FetchOutcome

    data class ServerError(val code: Int) : FetchOutcome

    data class NetworkError(val reason: String?) : FetchOutcome

    fun toScanStatus(): ScanStatus = when (this) {
        is Success -> ScanStatus.OK
        NeedsLogin -> ScanStatus.NEEDS_LOGIN
        NeedsVerification -> ScanStatus.NEEDS_VERIFICATION
        RateLimited -> ScanStatus.RATE_LIMITED
        is ServerError -> ScanStatus.NETWORK_ERROR
        is NetworkError -> ScanStatus.NETWORK_ERROR
    }
}

/** Which mechanism produced a response — surfaced in debug logs only. */
enum class Transport { HTTP, WEBVIEW }
