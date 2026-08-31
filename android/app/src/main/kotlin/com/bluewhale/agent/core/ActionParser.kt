package com.bluewhale.agent.core

import com.bluewhale.agent.model.AgentAction

object ActionParser {
    private val finishPattern =
        Regex("""finish\(message=["'](?<message>[\s\S]*)["']\)""")

    private val actionPattern =
        Regex("""do\(action=["'](?<action>[^"']+)["'](?<args>[^)]*)\)""")

    private val argPattern =
        Regex("""(\w+)=("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\[[^\]]*\]|[-\w.]+)""")

    fun parse(text: String): AgentAction? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val finish = finishPattern.findAll(trimmed).lastOrNull()
        finish?.groups?.get("message")?.value?.let { message ->
            return AgentAction(kind = "finish", message = message.trim())
        }

        val matches = actionPattern.findAll(trimmed).toList()
        val match = matches.lastOrNull() ?: return null
        val kind = match.groups["action"]?.value ?: return null
        val rawArgs = match.groups["args"]?.value.orEmpty()
        val params = mutableMapOf<String, Any?>()
        argPattern.findAll(rawArgs).forEach { arg ->
            val key = arg.groups[1]?.value ?: return@forEach
            val value = arg.groups[2]?.value ?: return@forEach
            params[key] = parseValue(value)
        }
        return AgentAction(kind = kind, params = params)
    }

    private fun parseValue(raw: String): Any? {
        val value = raw.trim()
        return when {
            (value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'")) ->
                value
                    .substring(1, value.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\\\", "\\")
            value.startsWith("[") && value.endsWith("]") ->
                Regex("""-?\d+""")
                    .findAll(value)
                    .map { it.value.toIntOrNull() ?: 0 }
                    .toList()
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> value
        }
    }
}