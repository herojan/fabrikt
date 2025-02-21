package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorUtils.addOptionalParameter
import com.cjbooms.fabrikt.generators.GeneratorUtils.firstResponse
import com.cjbooms.fabrikt.generators.GeneratorUtils.functionName
import com.cjbooms.fabrikt.generators.GeneratorUtils.getHeaderParams
import com.cjbooms.fabrikt.generators.GeneratorUtils.getPathParams
import com.cjbooms.fabrikt.generators.GeneratorUtils.getPrimaryContentMediaType
import com.cjbooms.fabrikt.generators.GeneratorUtils.getPrimaryContentMediaTypeKey
import com.cjbooms.fabrikt.generators.GeneratorUtils.getQueryParams
import com.cjbooms.fabrikt.generators.GeneratorUtils.hasMultipleContentMediaTypes
import com.cjbooms.fabrikt.generators.GeneratorUtils.primaryPropertiesConstructor
import com.cjbooms.fabrikt.generators.GeneratorUtils.toBodyParameterSpec
import com.cjbooms.fabrikt.generators.GeneratorUtils.toBodyRequestSchema
import com.cjbooms.fabrikt.generators.GeneratorUtils.toClassName
import com.cjbooms.fabrikt.generators.GeneratorUtils.toKCodeName
import com.cjbooms.fabrikt.generators.GeneratorUtils.toKdoc
import com.cjbooms.fabrikt.generators.GeneratorUtils.toParameterSpec
import com.cjbooms.fabrikt.generators.GeneratorUtils.toVarName
import com.cjbooms.fabrikt.generators.TypeFactory
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.ACCEPT_HEADER_VARIABLE_NAME
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.ADDITIONAL_HEADERS_PARAMETER_NAME
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.simpleClientName
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.toClientReturnType
import com.cjbooms.fabrikt.model.ClientType
import com.cjbooms.fabrikt.model.Destinations
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.HandlebarsTemplates
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.util.KaizenParserExtensions.routeToPaths
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.javaparser.utils.CodeGenerationUtils
import com.reprezen.kaizen.oasparser.model3.Operation
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName

class OkHttpSimpleClientGenerator(
    private val packages: Packages,
    private val api: SourceApi
) {
    fun generateDynamicClientCode(): Collection<ClientType> {
        return api.openApi3.routeToPaths().map { (resourceName, paths) ->
            val funcSpecs: List<FunSpec> = paths.flatMap { (resource, path) ->
                path.operations.map { (verb, operation) ->
                    FunSpec
                        .builder(functionName(resource, verb))
                        .addModifiers(KModifier.PUBLIC)
                        .addKdoc(operation.toKdoc())
                        .addAnnotation(
                            AnnotationSpec.builder(Throws::class)
                                .addMember("%T::class", "ApiException".toClassName(packages.client)).build()
                        )
                        .addParameters(operation.requestBody.toBodyParameterSpec(packages.base))
                        .addParameters(operation.parameters.map { it.toParameterSpec(packages.base) })
                        .addOptionalParameter(
                            ParameterSpec.builder(ACCEPT_HEADER_VARIABLE_NAME, String::class)
                                .defaultValue("%S", operation.getPrimaryContentMediaTypeKey())
                                .build(),
                            operation,
                        ) {
                            it.hasMultipleContentMediaTypes() == true &&
                                !operation.parameters.any { header -> header.name == "Accept" }
                        }
                        .addParameter(
                            ParameterSpec.builder(
                                ADDITIONAL_HEADERS_PARAMETER_NAME,
                                TypeFactory.createMapOfStringToType(String::class.asTypeName())
                            )
                                .defaultValue("emptyMap()")
                                .build()
                        )
                        .addCode(
                            SimpleClientOperationStatement(
                                packages,
                                resource,
                                verb,
                                operation
                            ).toStatement()
                        )
                        .returns(operation.toClientReturnType(packages))
                        .build()
                }
            }

            val clientType = TypeSpec.classBuilder(simpleClientName(resourceName))
                .primaryPropertiesConstructor(
                    PropertySpec.builder("objectMapper", ObjectMapper::class.asTypeName(), KModifier.PRIVATE).build(),
                    PropertySpec.builder("baseUrl", String::class.asTypeName(), KModifier.PRIVATE).build(),
                    PropertySpec.builder("client", "OkHttpClient".toClassName("okhttp3"), KModifier.PRIVATE).build()
                )
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
                .addFunctions(funcSpecs)
                .build()

            ClientType(clientType, packages.base)
        }.toSet()
    }

    fun generateLibrary(): Collection<GeneratedFile> {
        val codeDir = Destinations.MAIN_KT_SRC.resolve(CodeGenerationUtils.packageToPath(packages.base))
        val clientDir = codeDir.resolve("client")
        return setOf(
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientApiModels,
                packages,
                clientDir,
                "ApiModels.kt"
            ),
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientHttpUtils,
                packages,
                clientDir,
                "HttpUtil.kt"
            ),
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientOAuth,
                packages,
                clientDir,
                "OAuth.kt"
            ),
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientLoggingInterceptor,
                packages,
                clientDir,
                "LoggingInterceptor.kt"
            )
        )
    }
}

data class SimpleClientOperationStatement(
    private val packages: Packages,
    private val resource: String,
    private val verb: String,
    private val operation: Operation
) {
    fun toStatement(): CodeBlock =
        CodeBlock.builder()
            .addUrlStatement()
            .addPathParamStatement()
            .addQueryParamStatement()
            .addHeaderParamStatement()
            .addRequestStatement()
            .addRequestExecutionStatement()
            .build()

    private fun CodeBlock.Builder.addUrlStatement(): CodeBlock.Builder {
        this.add("val httpUrl: %T = \"%L\"", "HttpUrl".toClassName("okhttp3"), "\$baseUrl$resource")
        return this
    }

    private fun CodeBlock.Builder.addPathParamStatement(): CodeBlock.Builder {
        operation.getPathParams().map { this.add("\n.pathParam(%S to %N)", "{${it.name}}", it.name.toKCodeName()) }
        this.add("\n.%T()\n.newBuilder()", "toHttpUrl".toClassName("okhttp3.HttpUrl.Companion"))
        return this
    }

    /**
     * Only supports `form` style query params with either explode true or false. See [Open API 3.0
     * serialization](https://swagger.io/docs/specification/serialization) query parameters style values
     */
    private fun CodeBlock.Builder.addQueryParamStatement(): CodeBlock.Builder {
        operation.getQueryParams().map {
            when (KotlinTypeInfo.from(it.schema)) {
                is KotlinTypeInfo.Array -> this.add(
                    "\n.%T(%S, %N, %L)",
                    "queryParam".toClassName(packages.client),
                    it.name,
                    it.name.toKCodeName(),
                    if (it.explode == null || it.explode == true) "true" else "false"
                )
                else -> this.add(
                    "\n.%T(%S, %N)",
                    "queryParam".toClassName(packages.client),
                    it.name,
                    it.name.toKCodeName()
                )
            }
        }
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addHeaderParamStatement(): CodeBlock.Builder {
        this.add("\nval headerBuilder = Headers.Builder()")
        var isAcceptSet = false
        operation.getHeaderParams().map {
            if (it.name == "Accept") isAcceptSet = true
            val typeInfo = KotlinTypeInfo.from(it.schema)
            this.add(
                "\n.%T(%S, %L)", "header".toClassName(packages.client),
                it.name,
                it.name.toKCodeName() + if (typeInfo is KotlinTypeInfo.Enum) "?.value" else ""
            )
        }

        if (!isAcceptSet) operation.firstResponse()?.let {
            if (it.hasMultipleContentMediaTypes()) {
                this.add("\n.%T(%S, %L)", "header".toClassName(packages.client), "Accept", ACCEPT_HEADER_VARIABLE_NAME)
            } else {
                this.add(
                    "\n.%T(%S, %S)",
                    "header".toClassName(packages.client),
                    "Accept",
                    operation.getPrimaryContentMediaTypeKey()
                )
            }
        }
        this.add("\nadditionalHeaders.forEach { headerBuilder.header(it.key, it.value) }")

        return this.add("\nval httpHeaders: %T = headerBuilder.build()\n", "Headers".toClassName("okhttp3"))
    }

    private fun CodeBlock.Builder.addRequestStatement(): CodeBlock.Builder {
        this.add("\nval request: %T = Request.Builder()", "Request".toClassName("okhttp3"))
        this.add("\n.url(httpUrl)\n.headers(httpHeaders)")
        when (val op = verb.toUpperCase()) {
            "PUT" -> this.addRequestSerializerStatement("put")
            "POST" -> this.addRequestSerializerStatement("post")
            "PATCH" -> this.addRequestSerializerStatement("patch")
            "HEAD" -> this.add("\n.head()")
            "GET" -> this.add("\n.get()")
            "DELETE" -> this.add("\n.delete()")
            else -> throw NotImplementedError("API operation $op is not supported")
        }
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addRequestExecutionStatement() =
        this.add("\nreturn request.execute(client, objectMapper, jacksonTypeRef())")

    private fun CodeBlock.Builder.addRequestSerializerStatement(verb: String) {
        val requestBody = operation.requestBody
        requestBody.toBodyRequestSchema().firstOrNull()?.let {
            this.add(
                "\n.%N(objectMapper.writeValueAsString(%N).%T(%S.%T()))",
                verb,
                it.toVarName(),
                "toRequestBody".toClassName("okhttp3.RequestBody.Companion"),
                requestBody.getPrimaryContentMediaType()?.key,
                "toMediaType".toClassName("okhttp3.MediaType.Companion")
            )
        } ?: this.add("\n%N()", verb)
    }
}
