package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.util.KaizenParserExtensions.isEnumDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isInlineableMapDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isMapTypeAdditionalProperties
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isSchemaLess
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isTypedAdditionalProperties
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isUnknownAdditionalProperties
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isUntypedAdditionalProperties
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isUuidDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.safeType
import com.reprezen.kaizen.oasparser.model3.Schema
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

sealed class OasType(
    val type: String?,
    val format: String? = null,
    val specialization: Specialization = Specialization.NONE
) {
    object Boolean : OasType("boolean")
    object Date : OasType("string", "date")
    object DateTime : OasType("string", "date-time")
    object Text : OasType("string")
    object Float : OasType("number", "float")
    object Double : OasType("number", "double")
    object Number : OasType("number")
    object Int32 : OasType("integer", "int32")
    object Int64 : OasType("integer", "int64")
    object Integer : OasType("integer")
    object Object : OasType("object")
    object Array : OasType("array")
    object UntypedObject : OasType("object", specialization = Specialization.UNTYPED_OBJECT)
    object Enum : OasType("string", specialization = Specialization.ENUM)
    object Uuid : OasType("string", specialization = Specialization.UUID)
    object Map : OasType("object", specialization = Specialization.MAP)
    object UnknownAdditionalProperties : OasType("object", specialization = Specialization.UNKNOWN_ADDITIONAL_PROPERTIES)
    object UntypedObjectAdditionalProperties : OasType("object", specialization = Specialization.UNTYPED_OBJECT_ADDITIONAL_PROPERTIES)
    object TypedObjectAdditionalProperties : OasType("object", specialization = Specialization.TYPED_OBJECT_ADDITIONAL_PROPERTIES)
    object TypedMapAdditionalProperties : OasType("object", specialization = Specialization.TYPED_MAP_ADDITIONAL_PROPERTIES)
    companion object {
        fun Schema.toOasType(oasKey: String): OasType =
            values(OasType::class)
                .filter { it.type == safeType() }
                .filter { it.specialization == getSpecialization(oasKey) }
                .filter { it.format == format || it.format == null }
                .let { candidates ->
                    if (candidates.size > 1) candidates.find { it.format == format }
                    else candidates.firstOrNull()
                } ?: throw IllegalStateException("Unknown OAS type: ${safeType()} and format: $format")

        private fun values(clazz: KClass<OasType>) =
            clazz.nestedClasses.filter { it.isFinal && it.isSubclassOf(clazz) }
                .map { it.objectInstance as OasType }

        private fun Schema.getSpecialization(oasKey: String): Specialization =
            when {
                isUuidDefinition() -> Specialization.UUID
                isEnumDefinition() -> Specialization.ENUM
                isMapTypeAdditionalProperties(oasKey) -> Specialization.TYPED_MAP_ADDITIONAL_PROPERTIES
                isInlineableMapDefinition() -> Specialization.MAP
                isTypedAdditionalProperties(oasKey) -> Specialization.TYPED_OBJECT_ADDITIONAL_PROPERTIES
                isUntypedAdditionalProperties(oasKey) -> Specialization.UNTYPED_OBJECT_ADDITIONAL_PROPERTIES
                isUnknownAdditionalProperties(oasKey) -> Specialization.UNKNOWN_ADDITIONAL_PROPERTIES
                isSchemaLess() -> Specialization.UNTYPED_OBJECT
                else -> Specialization.NONE
            }
    }

    enum class Specialization {
        ENUM,
        MAP,
        UNKNOWN_ADDITIONAL_PROPERTIES,
        TYPED_OBJECT_ADDITIONAL_PROPERTIES,
        TYPED_MAP_ADDITIONAL_PROPERTIES,
        UNTYPED_OBJECT_ADDITIONAL_PROPERTIES,
        UNTYPED_OBJECT,
        UUID,
        NONE
    }
}
