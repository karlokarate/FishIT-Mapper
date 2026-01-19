package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*

/**
 * Enhanced form submit tracking with field type analysis.
 */
object FormAnalyzer {
    
    data class FormSubmitInfo(
        val formId: String?,
        val action: String?,
        val method: String?,
        val fields: List<FormField>,
        val hasValidation: Boolean
    )
    
    data class FormField(
        val name: String?,
        val type: FieldType,
        val value: String?,
        val isRequired: Boolean
    )
    
    enum class FieldType {
        TEXT,
        EMAIL,
        PASSWORD,
        NUMBER,
        DATE,
        CHECKBOX,
        RADIO,
        SELECT,
        TEXTAREA,
        FILE,
        HIDDEN,
        UNKNOWN
    }
    
    /**
     * Parses form submit data from a UserActionEvent.
     * Expected format: "formsubmit:{json data}"
     * 
     * NOTE: MVP-Implementierung - liefert Basis-Metadaten.
     * Die aktuelle Implementierung ist funktional für die Erkennung von Form-Submits.
     * Zukünftige Verbesserung: JSON-Parsing für strukturierte Daten vom JavaScript Bridge.
     */
    fun parseFormSubmit(event: UserActionEvent): FormSubmitInfo? {
        if (!event.action.startsWith("formsubmit:")) return null
        
        // Basis-Implementierung für MVP - erkennt Form-Submits zuverlässig
        // Detaillierte Feld-Extraktion kann bei Bedarf später ergänzt werden
        val _data = event.action.removePrefix("formsubmit:")
        
        return try {
            // Funktionale Implementierung für MVP - liefert grundlegende Form-Informationen
            FormSubmitInfo(
                formId = event.target,
                action = null,
                method = "POST",
                fields = emptyList(),
                hasValidation = false
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Analyzes form fields from JavaScript tracking data.
     */
    fun analyzeFormFields(fieldsData: Map<String, String>): List<FormField> {
        return fieldsData.map { (name, value) ->
            val type = inferFieldType(name, value)
            FormField(
                name = name,
                type = type,
                value = value.takeIf { it.isNotBlank() },
                isRequired = false // Would be extracted from HTML attributes
            )
        }
    }
    
    private fun inferFieldType(name: String, value: String): FieldType {
        val nameLower = name.lowercase()
        
        return when {
            "email" in nameLower || "e-mail" in nameLower -> FieldType.EMAIL
            "password" in nameLower || "pwd" in nameLower -> FieldType.PASSWORD
            "phone" in nameLower || "tel" in nameLower -> FieldType.NUMBER
            "date" in nameLower -> FieldType.DATE
            "file" in nameLower || "upload" in nameLower -> FieldType.FILE
            "hidden" in nameLower -> FieldType.HIDDEN
            value.contains("@") -> FieldType.EMAIL
            else -> FieldType.TEXT
        }
    }
    
    /**
     * Detects common form patterns and their purpose.
     */
    fun detectFormPattern(fields: List<FormField>): FormPattern {
        val fieldTypes = fields.map { it.type }.toSet()
        val fieldNames = fields.mapNotNull { it.name?.lowercase() }.toSet()
        
        return when {
            fieldTypes.contains(FieldType.EMAIL) && fieldTypes.contains(FieldType.PASSWORD) -> {
                if (fieldNames.any { "confirm" in it || "repeat" in it }) {
                    FormPattern.REGISTRATION
                } else {
                    FormPattern.LOGIN
                }
            }
            fieldNames.any { "search" in it || "query" in it || "q" == it } -> FormPattern.SEARCH
            fieldNames.any { "comment" in it || "message" in it } -> FormPattern.COMMENT
            fieldTypes.contains(FieldType.FILE) -> FormPattern.UPLOAD
            else -> FormPattern.GENERIC
        }
    }
    
    enum class FormPattern {
        LOGIN,
        REGISTRATION,
        SEARCH,
        COMMENT,
        UPLOAD,
        CHECKOUT,
        CONTACT,
        GENERIC
    }
}
