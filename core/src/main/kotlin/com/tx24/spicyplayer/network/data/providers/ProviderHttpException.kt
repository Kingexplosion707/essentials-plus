package com.tx24.spicyplayer.network.data.providers

import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import com.tx24.spicyplayer.network.data.ProviderResult
import java.io.IOException

class ProviderHttpException(val provider: String, val code: Int) : IOException("$provider HTTP $code") {
    fun unavailable() = ProviderResult.Unavailable(
        category = when (code) {
            401, 403 -> ProviderFailureCategory.AUTHENTICATION
            in 400..499 -> ProviderFailureCategory.CLIENT_REQUEST
            else -> ProviderFailureCategory.SERVER
        },
        message = message,
        retryable = code >= 500,
    )
}
