package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.generators.model.JacksonModelGenerator.Companion.toModelType
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.reprezen.kaizen.oasparser.model3.MediaType
import com.reprezen.kaizen.oasparser.model3.Operation
import com.reprezen.kaizen.oasparser.model3.Parameter
import com.reprezen.kaizen.oasparser.model3.RequestBody
import com.reprezen.kaizen.oasparser.model3.Response
import com.reprezen.kaizen.oasparser.model3.Schema
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import java.util.function.Predicate

object GeneratorUtils {
    /**
     * It resolves the API operation body request to its body type. If multiple content medias are found, then it will
     * resolve to the schema reference of the first media type, otherwise it assumes no request body defined for
     * the given operation.
     */
    fun RequestBody.toBodyParameterSpec(basePackage: String): List<ParameterSpec> =
        this.toBodyRequestSchema().map {
            val modelType = toModelType(
                basePackage = basePackage,
                typeInfo = KotlinTypeInfo.from(it),
                isNullable = !this.isRequired
            )
            ParameterSpec.builder(
                it.toVarName(),
                modelType
            ).build()
        }

    /**
     * It resolves the given either `query` of `form` parameter to its corresponding function param specification.
     */
    fun Parameter.toParameterSpec(basePackage: String): ParameterSpec =
        ParameterSpec.builder(
            this.name.toKCodeName(),
            toModelType(
                basePackage = basePackage,
                typeInfo = KotlinTypeInfo.from(this.schema),
                isNullable = !this.isRequired && this.schema.default == null
            )
        ).build()

    /**
     * It converts any string to a variable or function name by removing all non-letter-or-digit characters and transforms
     * the in-between resulting words into `camel case`.
     * e.g. `GET /my-resource/path/{param}` -> getMyResourcePathParam
     */
    fun String.toKCodeName(): String {
        val delimiters = this.partition(Char::isLetterOrDigit).second.toCharArray().map(Char::toString).toTypedArray()
        return this.splitToSequence(*delimiters)
            .mapNotNull(String::capitalize)
            .joinToString("")
            .decapitalize()
    }

    /**
     * It resolves the schema for the given API operation. If multiple content medias are found, then it will
     * resolve to the schema reference of the first media type.
     */
    fun RequestBody.toBodyRequestSchema(): List<Schema> =
        listOfNotNull(this.getPrimaryContentMediaType()?.value?.schema)

    fun Operation.toKdoc(): CodeBlock {
        val kdoc = CodeBlock.builder().add("${this.summary.orEmpty()}\n${this.description.orEmpty()}\n")

        this.parameters.forEach {
            kdoc.add("@param %L %L\n", it.name.toKCodeName(), it.description.orEmpty()).build()
        }

        return kdoc.build()
    }

    fun TypeSpec.Builder.primaryPropertiesConstructor(vararg properties: PropertySpec): TypeSpec.Builder {
        val propertySpecs = properties.map { it.toBuilder().initializer(it.name).build() }
        val parameters = propertySpecs.map { ParameterSpec.builder(it.name, it.type).build() }
        val constructor = FunSpec.constructorBuilder().addParameters(parameters).build()
        return this.primaryConstructor(constructor).addProperties(propertySpecs)
    }

    fun <T> FunSpec.Builder.addOptionalParameter(
        parameterSpec: ParameterSpec,
        input: T,
        predicate: Predicate<T>
    ) = apply {
        if (predicate.test(input))
            this.addParameter(parameterSpec)
    }

    fun functionName(resource: String, verb: String) = "$verb $resource".toKCodeName()

    fun Schema.toVarName() = this.name?.toKCodeName() ?: this.toClassName().simpleName.toKCodeName()

    private fun Schema.toClassName() = KotlinTypeInfo.from(this).modelKClass.asTypeName()

    fun String.toClassName(basePackage: String) = ClassName(packageName = basePackage, simpleName = this)

    fun RequestBody.getPrimaryContentMediaType(): Map.Entry<String, MediaType>? =
        this.contentMediaTypes.entries.firstOrNull()

    fun Response.getPrimaryContentMediaType(): Map.Entry<String, MediaType>? =
        this.contentMediaTypes.entries.firstOrNull()

    fun Response.hasMultipleContentMediaTypes(): Boolean = this.contentMediaTypes.entries.size > 1

    fun Operation.firstResponse(): Response? = this.getBodyResponses().firstOrNull()

    fun Operation.getPrimaryContentMediaType(): Map.Entry<String, MediaType>? =
        this.getBodyResponses().map { response -> response.getPrimaryContentMediaType() }.firstOrNull()

    fun Operation.getPrimaryContentMediaTypeKey(): String? = this.firstResponse()?.getPrimaryContentMediaType()?.key

    fun Operation.hasMultipleContentMediaTypes(): Boolean? = this.firstResponse()?.hasMultipleContentMediaTypes()

    fun Operation.getPathParams(): List<Parameter> = this.filterParams("path")

    fun Operation.getQueryParams(): List<Parameter> = this.filterParams("query")

    fun Operation.getHeaderParams(): List<Parameter> = this.filterParams("header")

    private fun Operation.getBodyResponses(): List<Response> =
        this.responses.filter { it.key != "default" }.values.filter(Response::hasContentMediaTypes)

    private fun Operation.filterParams(paramType: String): List<Parameter> = this.parameters.filter { it.`in` == paramType }
}
