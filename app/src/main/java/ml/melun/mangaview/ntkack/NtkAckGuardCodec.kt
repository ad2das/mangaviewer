package ml.melun.mangaview.ntkack

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Converts the exact versioned network pair into the exact bytes executed by the ACK WebView. */
object NtkAckGuardCodec {
    data class ExecutablePair(val javascript: ByteArray, val wasm: ByteArray)

    private const val ENCRYPTED_MARKER = "/*!ENCRYPTED*/"
    private val seedPattern = Regex(
        "(?:var|let|const)\\s+\\w+\\s*=\\s*(\\[\\[[0-9,\\]\\[\\s-]+\\]\\])\\s*," +
            "\\s*\\w+\\s*=\\s*(\\[\\[[0-9,\\]\\[\\s-]+\\]\\])\\s*,",
    )
    private val exportPattern = Regex(
        "export\\{\\s*([A-Za-z_\u0024][\\w\u0024]*)\\s+as\\s+initSync\\s*," +
            "\\s*([A-Za-z_\u0024][\\w\u0024]*)\\s+as\\s+default\\s*\\}",
    )
    private val existingDefaultPattern = Regex("export\\{[^}]*\\bas\\s+default\\b")

    fun decode(javascriptBytes: ByteArray, wasmBytes: ByteArray): ExecutablePair {
        require(javascriptBytes.isNotEmpty()) { "Guard JS is empty" }
        require(wasmBytes.size > 4) { "Guard WASM is empty" }
        val text = javascriptBytes.toString(StandardCharsets.UTF_8)
        val marker = text.indexOf(ENCRYPTED_MARKER)
        if (marker < 0) {
            require(isWasm(wasmBytes)) { "Guard WASM is encrypted but loader has no versioned seed" }
            return ExecutablePair(javascriptBytes.copyOf(), wasmBytes.copyOf())
        }
        val encryptedTail = text.substring(marker)
        var executableJavascript = text.substring(0, marker).trimEnd()
        if (!existingDefaultPattern.containsMatchIn(executableJavascript)) {
            val exports = exportPattern.find(encryptedTail)
                ?: throw IllegalArgumentException("Guard encrypted loader export aliases are missing")
            executableJavascript +=
                "\nexport{${exports.groupValues[1]} as initSync,${exports.groupValues[2]} as default};"
        }
        val seeds = seedPattern.find(encryptedTail)
            ?: throw IllegalArgumentException("Guard encrypted loader seeds are missing")
        val hmacSeed = parseMatrix(seeds.groupValues[1], 4, 4)
        val aesSeed = parseMatrix(seeds.groupValues[2], 8, 4)
        val rawWasm = decrypt(wasmBytes, hmacSeed, aesSeed)
        require(isWasm(rawWasm)) { "Guard WASM decryption did not produce a module" }
        return ExecutablePair(executableJavascript.toByteArray(StandardCharsets.UTF_8), rawWasm)
    }

    private fun parseMatrix(text: String, rows: Int, columns: Int): Array<IntArray> {
        val matches = Regex("\\[([^\\[\\]]+)]").findAll(text).toList()
        require(matches.size == rows) { "Guard seed row count mismatch" }
        return Array(rows) { row ->
            val values = matches[row].groupValues[1].split(',').map(String::trim)
            require(values.size == columns) { "Guard seed column count mismatch" }
            IntArray(columns) { column -> values[column].toInt() }
        }
    }

    private fun decrypt(encrypted: ByteArray, hmacSeed: Array<IntArray>, aesSeed: Array<IntArray>): ByteArray {
        require(encrypted.size > 28) { "Guard encrypted WASM is truncated" }
        val hmacKey = ByteArray(16)
        for (row in 0 until 4) {
            val mask = (163 + 71 * row) and 255
            for (column in 0 until 4) hmacKey[4 * row + column] = (hmacSeed[row][column] xor mask).toByte()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val digest = mac.doFinal("ntk-ad-guard-v2".toByteArray(StandardCharsets.UTF_8))

        val aesMaterial = ByteArray(32)
        val order = intArrayOf(3, 0, 6, 1, 4, 7, 2, 5)
        for (row in 0 until 8) {
            val destination = order[row]
            val mask = (93 + 43 * destination + 17 * row) and 255
            for (column in 0 until 4) {
                aesMaterial[4 * destination + column] = (aesSeed[row][column] xor mask).toByte()
            }
        }
        val aesKey = ByteArray(32) { index -> (aesMaterial[index].toInt() xor digest[index].toInt()).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, encrypted.copyOfRange(0, 12)),
        )
        val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        for (index in plain.indices) plain[index] = (plain[index].toInt() xor digest[index % digest.size].toInt()).toByte()
        return plain
    }

    private fun isWasm(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0x61.toByte() &&
            bytes[2] == 0x73.toByte() && bytes[3] == 0x6d.toByte()
}
