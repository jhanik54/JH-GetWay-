package com.example.core.security

import java.net.URI

object MaskingUtils {
    fun maskSecret(secret: String): String {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.length <= 6 -> "••••••"
            trimmed.length <= 12 -> "${trimmed.take(2)}••••${trimmed.takeLast(2)}"
            else -> "${trimmed.take(5)}••••••${trimmed.takeLast(4)}"
        }
    }

    fun sanitizeErrorMessage(message: String?, secretsToStrip: List<String> = emptyList()): String {
        var sanitized = message?.trim().orEmpty()
        if (sanitized.isEmpty()) return "Unknown error"
        secretsToStrip.filter { it.length >= 4 }.forEach { secret ->
            sanitized = sanitized.replace(secret, "[REDACTED]")
        }
        // Strip common key patterns
        sanitized = sanitized
            .replace(Regex("(sk-[A-Za-z0-9_\\-]{8,})"), "[REDACTED_KEY]")
            .replace(Regex("(AIza[A-Za-z0-9_\\-]{12,})"), "[REDACTED_KEY]")
            .replace(Regex("(jhgw_[A-Za-z0-9_\\-]{8,})"), "[REDACTED_TOKEN]")
            .replace(Regex("(Bearer\\s+[A-Za-z0-9._\\-]{8,})", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .replace(Regex("([?&]key=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1[REDACTED]")
        return sanitized.take(320)
    }
}

object NetworkAddressClassifier {
    fun isPrivateOrLoopbackHost(urlString: String): Boolean {
        val host = parseHost(urlString)?.lowercase() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") return true
        if (host.endsWith(".local")) return true
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10) return true
            if (a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
        }
        return false
    }

    fun isSecureOrAllowedLocalUrl(urlString: String, allowLocalHttp: Boolean): Boolean {
        return try {
            val uri = URI(urlString.trim())
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host ?: return false
            if (host.isBlank()) return false
            when (scheme) {
                "https" -> true
                "http" -> allowLocalHttp && isPrivateOrLoopbackHost(urlString)
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun parseHost(urlString: String): String? {
        return try {
            URI(urlString.trim()).host
        } catch (_: Exception) {
            null
        }
    }
}
