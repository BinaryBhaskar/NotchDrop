package com.amurot.notchdrop.core

import com.amurot.notchdrop.service.Updates
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesVersions() {
        val cases = listOf(
            Triple("v1.2.0", "1.1.1", true),
            Triple("1.10.0", "1.9.9", true),
            Triple("1.1.1", "1.1.1", false),
            Triple("1.1", "1.1.0", false),
            Triple("v1.0.9", "1.1.0", false),
            Triple("2.0.0-beta", "1.9", true),
        )
        for ((a, b, want) in cases) assertEquals("$a vs $b", want, Updates.isNewer(a, b))
    }
}
