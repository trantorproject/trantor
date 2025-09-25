package dev.botta.trantor.web.client

import java.io.InputStream

class MultipartBody {
    val parts = mutableListOf<Part>()

    fun addFieldPart(name: String, value: String?, fields: Map<String, String>? = null) = apply {
        parts.add(FieldPart(name, value, fields))
    }

    fun addFilePart(name: String, fileName: String, mimeType: String, data: InputStream, fields: Map<String, String>? = null) = apply {
        parts.add(FilePart(name, fileName, mimeType, data, fields))
    }

    interface Part {
        val name: String
        val fields: Map<String, String>?
    }

    data class FieldPart(
        override val name: String,
        val value: String?,
        override val fields: Map<String, String>?,
    ): Part

    data class FilePart(
        override val name: String,
        val fileName: String,
        val mimeType: String,
        val data: InputStream,
        override val fields: Map<String, String>?,
    ): Part
}
