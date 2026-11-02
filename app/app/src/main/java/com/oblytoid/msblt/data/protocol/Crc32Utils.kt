package com.oblytoid.msblt.data.protocol

import com.oblytoid.msblt.data.bluetooth.AnimalLogRecord
import java.util.Locale
import java.util.zip.CRC32

object Crc32Utils {

    fun calculateCrc32(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return String.format(Locale.US, "%08X", crc.value)
    }

    fun calculateCrc32(text: String): String {
        return calculateCrc32(text.toByteArray(Charsets.UTF_8))
    }

    fun verifyCrc32(bytes: ByteArray, expectedHexCrc: String): Boolean {
        if (expectedHexCrc.isBlank()) return true
        val computed = calculateCrc32(bytes)
        return computed.equals(expectedHexCrc.trim(), ignoreCase = true)
    }

    fun cleanEncodingArtifacts(text: String): String {
        return text
            .replace("SAÃ\u008dDA", "SAÍDA")
            .replace("SAÃ\u008d", "SAÍ")
            .replace("SAÃIDA", "SAÍDA")
            .replace("Â°C", "°C")
            .replace("Â°", "°")
    }

    fun parseAnimalLogCsv(rawCsvContent: String): List<AnimalLogRecord> {
        val records = mutableListOf<AnimalLogRecord>()
        val cleanContent = cleanEncodingArtifacts(rawCsvContent)
        val lines = cleanContent.lineSequence()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("sep=", ignoreCase = true) ||
                trimmed.startsWith("RFID", ignoreCase = true) || trimmed.startsWith("TAG_ID", ignoreCase = true)) {
                continue // Skip headers and comments
            }

            // Split by semicolon ';' first if available, otherwise by comma ',' or tab '\t'
            val delimiter = if (trimmed.contains(";")) ";" else if (trimmed.contains(",")) "," else "\t"
            val parts = trimmed.split(delimiter)

            if (parts.size >= 2) {
                val rfid = parts[0].trim()
                var eventType = parts.getOrNull(1)?.trim() ?: "ENTRADA"
                val timestamp = parts.getOrNull(2)?.trim() ?: "N/A"
                var temperature = parts.getOrNull(3)?.trim() ?: "N/A"
                val humidity = parts.getOrNull(4)?.trim() ?: "N/A"

                if (eventType.contains("SA", ignoreCase = true) && !eventType.contains("ENTRADA", ignoreCase = true)) {
                    eventType = "SAÍDA"
                } else if (eventType.contains("ENTRADA", ignoreCase = true)) {
                    eventType = "ENTRADA"
                }

                if (!temperature.contains("°") && temperature.matches(Regex("^[0-9]+(\\.[0-9]+)?$"))) {
                    temperature = "$temperature°C"
                }

                records.add(
                    AnimalLogRecord(
                        tagId = rfid,
                        eventType = eventType,
                        timestamp = timestamp,
                        temperature = temperature,
                        humidity = humidity,
                        rawEntry = trimmed
                    )
                )
            }
        }
        return records
    }

    /**
     * Converts raw CSV bytes or strings into Excel-compatible UTF-8 BOM CSV format
     * using semicolon ';' delimiter so Microsoft Excel / Google Sheets automatically
     * separates columns.
     */
    fun formatToExcelCompatibleCsv(rawContent: String): ByteArray {
        val records = parseAnimalLogCsv(rawContent)
        val csvBuilder = StringBuilder()

        // Excel header directive and CSV column header
        csvBuilder.append("sep=;\n")
        csvBuilder.append("RFID;TIPO_EVENTO;DATA_HORA;TEMPERATURA;UMIDADE\n")

        for (rec in records) {
            csvBuilder.append("${rec.tagId};${rec.eventType};${rec.timestamp};${rec.temperature};${rec.humidity}\n")
        }

        val utf8Bytes = csvBuilder.toString().toByteArray(Charsets.UTF_8)
        // Prepend UTF-8 BOM (0xEF, 0xBB, 0xBF) for Excel on Windows
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        val result = ByteArray(bom.size + utf8Bytes.size)
        System.arraycopy(bom, 0, result, 0, bom.size)
        System.arraycopy(utf8Bytes, 0, result, bom.size, utf8Bytes.size)

        return result
    }
}
