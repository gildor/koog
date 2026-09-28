package io.github.gildor.koog.nativeagents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

private val exampleJson = ObjectMapper()
internal fun obj(vararg fields: Pair<String, Any?>): JsonNode = exampleJson.valueToTree(fields.toMap())
