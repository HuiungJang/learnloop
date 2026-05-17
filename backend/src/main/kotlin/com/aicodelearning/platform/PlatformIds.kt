package com.aicodelearning.platform

import java.util.UUID

fun prefixedId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"
