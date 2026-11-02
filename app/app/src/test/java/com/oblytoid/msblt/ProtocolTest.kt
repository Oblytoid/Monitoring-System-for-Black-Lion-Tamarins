package com.oblytoid.msblt

import com.oblytoid.msblt.data.protocol.Crc32Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun testCrc32CalculationAndVerification() {
        val sampleData = "982000012389102, ENTRADA, 25/09/2026 14:30:00, 36.8°C, 64%".toByteArray(Charsets.UTF_8)
        val computedCrc = Crc32Utils.calculateCrc32(sampleData)

        assertTrue(computedCrc.isNotBlank())
        assertEquals(8, computedCrc.length)

        assertTrue(Crc32Utils.verifyCrc32(sampleData, computedCrc))
        assertFalse(Crc32Utils.verifyCrc32(sampleData, "00000000"))
    }

    @Test
    fun testCsvLogParsingWith5Fields() {
        val rawCsv = """
            # Header Comment
            RFID, ENTRADA_SAIDA, DATA_HORA, TEMPERATURA, UMIDADE
            982000012389102, ENTRADA, 25/09/2026 14:30:00, 36.8°C, 64%
            982000012389103, SAÍDA, 25/09/2026 14:35:12, 37.1°C, 62%
        """.trimIndent()

        val records = Crc32Utils.parseAnimalLogCsv(rawCsv)

        assertEquals(2, records.size)

        assertEquals("982000012389102", records[0].tagId)
        assertEquals("ENTRADA", records[0].eventType)
        assertEquals("25/09/2026 14:30:00", records[0].timestamp)
        assertEquals("36.8°C", records[0].temperature)
        assertEquals("64%", records[0].humidity)

        assertEquals("982000012389103", records[1].tagId)
        assertEquals("SAÍDA", records[1].eventType)
        assertEquals("37.1°C", records[1].temperature)
        assertEquals("62%", records[1].humidity)
    }

    @Test
    fun testParsingFirmwareRecordFormat() {
        // Exact format written by the reader firmware: RFID;EVENT;DATE_TIME;TEMP;HUMIDITY;PRESSURE
        val rawCsv = """
            900263006666063;ENTRADA;05/10/2026 06:14:22;23.8;75.0%;941.7
            900263006666063;SAÍDA;05/10/2026 06:48:19;24.2;71.0%;941.5
        """.trimIndent()

        val records = Crc32Utils.parseAnimalLogCsv(rawCsv)

        assertEquals(2, records.size)
        assertEquals("900263006666063", records[0].tagId)
        assertEquals("ENTRADA", records[0].eventType)
        assertEquals("05/10/2026 06:14:22", records[0].timestamp)
        assertEquals("23.8°C", records[0].temperature)
        assertEquals("75.0%", records[0].humidity)
        assertEquals("SAÍDA", records[1].eventType)
    }
}
