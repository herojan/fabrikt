package com.cjbooms.fabrikt.generators

import com.beust.jcommander.ParameterException
import com.cjbooms.fabrikt.cli.CodeGenerationType
import com.cjbooms.fabrikt.cli.ModelCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.model.JacksonModelGenerator
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.util.Linter
import com.cjbooms.fabrikt.util.ResourceHelper.readTextResource
import com.squareup.kotlinpoet.FileSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelGeneratorTest {

    @Suppress("unused")
    private fun testCases(): Stream<String> = Stream.of(
        "arrays",
        "anyOfOneOfAllOf",
        "deepNestedSharingReferences",
        "defaultValues",
        "duplicatePropertyHandling",
        "enumExamples",
        "enumPolymorphicDiscriminator",
        "externalReferences",
        "githubApi",
        "inLinedObject",
        "mapExamples",
        "mixingCamelSnakeLispCase",
        "oneOfPolymorphicModels",
        "optionalVsRequired",
        "polymorphicModels",
        "requiredReadOnly",
        "validationAnnotations",
        "wildCardTypes"
    )

    @BeforeEach
    fun init() {
        MutableSettings.updateSettings(setOf(CodeGenerationType.HTTP_MODELS), emptySet(), emptySet())
    }

    @ParameterizedTest
    @MethodSource("testCases")
    fun `correct models are generated for different OpenApi Specifications`(testCaseName: String) {
        print("Testcase: $testCaseName")
        MutableSettings.addOption(ModelCodeGenOptionType.X_EXTENSIBLE_ENUMS)
        val basePackage = "examples.$testCaseName"
        val apiLocation = javaClass.getResource("/examples/$testCaseName/api.yaml")!!
        val sourceApi = SourceApi(apiLocation.readText(), baseDir = Paths.get(apiLocation.toURI()))
        val expectedModels = readTextResource("/examples/$testCaseName/models/Models.kt")

        val models = JacksonModelGenerator(
            Packages(basePackage),
            sourceApi
        ).generate().toSingleFile()

        assertThat(models).isEqualTo(expectedModels)
    }

    @Test
    fun `sealed classes are correctly grouped into a single file`() {
        val basePackage = "examples.polymorphicModels.sealed"
        val spec = readTextResource("/examples/polymorphicModels/api.yaml")
        val expectedModels = readTextResource("/examples/polymorphicModels/sealed/models/Models.kt")

        val models = JacksonModelGenerator(
            Packages(basePackage),
            SourceApi(spec)
        ).generate()

        assertThat(Linter.lintString(models.files.first().toString())).isEqualTo(expectedModels)
    }

    @Test
    fun `serializable models are generated from a full API definition when the java-serialized option is set`() {
        val basePackage = "examples.javaSerializableModels"
        val spec = readTextResource("/examples/javaSerializableModels/api.yaml")
        val expectedModels = readTextResource("/examples/javaSerializableModels/models/Models.kt")

        val models = JacksonModelGenerator(
            Packages(basePackage),
            SourceApi(spec),
            setOf(ModelCodeGenOptionType.JAVA_SERIALIZATION)
        )
            .generate()
            .toSingleFile()

        assertThat(models).isEqualTo(expectedModels)
    }

    @Test
    fun `missing array reference throws constructive message`() = assertExceptionWithMessage(
        "/badInput/ErrorMissingRefArray.yaml",
        "Array type 'hooks' cannot be parsed to a Schema. Check your input"
    )

    @Test
    fun `missing object reference throws constructive message`() = assertExceptionWithMessage(
        "/badInput/ErrorMissingRefObject.yaml",
        "Property 'propB' cannot be parsed to a Schema. Check your input"
    )

    @Test
    fun `mixing oneOf with object type throws constructive error`() = assertExceptionWithMessage(
        "/badInput/ErrorMixingOneOfWithObject.yaml",
        "schema contains an invalid combination of properties and `oneOf | anyOf | allOf`"
    )

    @Test
    fun `mixing anyOf with object type throws constructive error`() = assertExceptionWithMessage(
        "/badInput/ErrorMixingAnyOfWithObject.yaml",
        "schema contains an invalid combination of properties and `oneOf | anyOf | allOf`"
    )

    @Test
    fun `mixing allOf with object type throws constructive error`() = assertExceptionWithMessage(
        "/badInput/ErrorMixingAllOfWithObject.yaml",
        "schema contains an invalid combination of properties and `oneOf | anyOf | allOf`"
    )

    private fun assertExceptionWithMessage(path: String, expectedMessage: String) {
        val spec = readTextResource(path)
        val exception = assertThrows<ParameterException> {
            JacksonModelGenerator(Packages("blah"), SourceApi(spec))
                .generate()
                .toSingleFile()
        }
        assertThat(exception.message).contains(expectedMessage)
    }

    @Test
    fun `quarkus reflection models are generated from a full API definition when the quarkus-reflection option is set`() {
        val basePackage = "examples.quarkusReflectionModels"
        val spec = readTextResource("/examples/quarkusReflectionModels/api.yaml")
        val expectedModels = readTextResource("/examples/quarkusReflectionModels/models/Models.kt")

        val models = JacksonModelGenerator(
            Packages(basePackage),
            SourceApi(spec),
            setOf(ModelCodeGenOptionType.QUARKUS_REFLECTION)
        )
            .generate()
            .toSingleFile()

        assertThat(models).isEqualTo(expectedModels)
    }

    @Test
    fun `micronaut introspected models are generated from a full API definition when the micronaut-introspection option is set`() {
        val basePackage = "examples.quarkusReflectionModels"
        val spec = readTextResource("/examples/micronautIntrospectedModels/api.yaml")
        val expectedModels = readTextResource("/examples/micronautIntrospectedModels/models/Models.kt")

        val models = JacksonModelGenerator(
            Packages(basePackage),
            SourceApi(spec),
            setOf(ModelCodeGenOptionType.MICRONAUT_INTROSPECTION)
        )
            .generate()
            .toSingleFile()

        assertThat(models).isEqualTo(expectedModels)
    }

    private fun Models.toSingleFile(): String {
        val destPackage = if (models.isNotEmpty()) models.first().destinationPackage else ""
        val singleFileBuilder = FileSpec.builder(destPackage, "dummyFilename")
        models.forEach {
            val builder = singleFileBuilder
                .addType(it.spec)
            builder.build()
        }
        return Linter.lintString(singleFileBuilder.build().toString())
    }
}
