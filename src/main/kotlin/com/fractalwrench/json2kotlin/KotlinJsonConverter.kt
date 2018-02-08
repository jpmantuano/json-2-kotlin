package com.fractalwrench.json2kotlin

import com.google.gson.*
import com.squareup.kotlinpoet.*
import java.io.OutputStream
import java.util.*

class KotlinJsonConverter(private val jsonParser: JsonParser) {

    private var sourceFile: FileSpec.Builder = FileSpec.builder("", "")
    private val stack = Stack<TypeSpec.Builder>()

    fun convert(input: String, output: OutputStream, args: ConversionArgs) {
        try {
            if (input.isEmpty()) {
                throw IllegalArgumentException("Json input empty")
            }
            val jsonRoot = jsonParser.parse(input)
            sourceFile = FileSpec.builder("", args.rootClassName)

            // TODO build up a Set of all the objects as a type representation

            when {
                jsonRoot.isJsonObject -> processJsonObject(jsonRoot.asJsonObject, args.rootClassName)
                jsonRoot.isJsonArray -> handleRootJsonArray(jsonRoot.asJsonArray, args.rootClassName)
                else -> throw IllegalStateException("Expected a JSON array or object")
            }

            while (!stack.isEmpty()) {
                sourceFile.addType(stack.pop().build())
            }

            val stringBuilder = StringBuilder()
            sourceFile.build().writeTo(stringBuilder)
            output.write(stringBuilder.toString().toByteArray())

        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Invalid JSON supplied", e)
        }
    }

    /**
     * Adds a wrapper object around the array
     */
    private fun handleRootJsonArray(jsonArray: JsonArray, className: String) {
        val fieldName = "${className}Field".decapitalize()
        val containerClassName = "${className}Container"
        val jsonElement = JsonObject().apply { add(fieldName, jsonArray) }
        processJsonObject(jsonElement, containerClassName)
    }

    private fun processJsonObject(jsonObject: JsonObject, className: String) {
        val classBuilder = TypeSpec.classBuilder(className)

        if (jsonObject.size() > 0) {
            val constructor = FunSpec.constructorBuilder()
            classBuilder.addModifiers(KModifier.DATA) // non-empty classes allow data modifier

            // find the type for each value then add the field to the class
            for (entry in jsonObject.entrySet()) {
                val valueType = findJsonValueType(entry.value, entry.key)
                addDataClassProperty(entry.key, valueType, constructor, classBuilder)
            }
            classBuilder.primaryConstructor(constructor.build())
        }
        stack.add(classBuilder)
    }

    private fun addDataClassProperty(key: String, type: TypeName,
                                     constructor: FunSpec.Builder, classBuilder: TypeSpec.Builder) {
        val initializer = PropertySpec.builder(key, type)
                .initializer(key)
        classBuilder.addProperty(initializer.build()) // ensures val present by adding both
        constructor.addParameter(key, type)
    }


    private fun findJsonValueType(jsonElement: JsonElement, key: String): TypeName {
        return when {
            jsonElement.isJsonPrimitive -> findJsonPrimitiveType(jsonElement.asJsonPrimitive)
            jsonElement.isJsonArray -> findJsonArrayType(jsonElement.asJsonArray, key)
            jsonElement.isJsonObject -> findJsonObjectType(jsonElement.asJsonObject, key)
            jsonElement.isJsonNull -> Any::class.asTypeName().asNullable()
            else -> throw IllegalStateException("Expected a JSON value")
        }
    }

    private fun findJsonPrimitiveType(primitive: JsonPrimitive): TypeName {
        return when {
            primitive.isBoolean -> Boolean::class
            primitive.isNumber -> Number::class
            primitive.isString -> String::class
            else -> throw IllegalStateException("No type found for JSON primitive " + primitive)
        }.asTypeName()
    }

    private fun findJsonArrayType(jsonArray: JsonArray, key: String): TypeName {
        val arrayTypes = HashSet<TypeName>()
        var nullable = false

        for (jsonElement in jsonArray) { // TODO optimise by checking arrayTypes each iteration
            // TODO find key!
            when {
                jsonElement.isJsonPrimitive -> arrayTypes.add(findJsonValueType(jsonElement.asJsonPrimitive, key))
                jsonElement.isJsonArray -> arrayTypes.add(findJsonArrayType(jsonElement.asJsonArray, "${key}Array"))
                jsonElement.isJsonObject -> arrayTypes.add(findJsonObjectType(jsonElement.asJsonObject, "${key}Object"))
                jsonElement.isJsonNull -> nullable = true
                else -> throw IllegalStateException("Unexpected state in array")
            }
        }

        val rawType = deduceArrayType(arrayTypes, nullable)
        return ParameterizedTypeName.get(Array<Any>::class.asClassName(), rawType)
    }

    private fun deduceArrayType(arrayTypes: HashSet<TypeName>, nullable: Boolean): TypeName {
        val typeName = if (arrayTypes.size > 1 || arrayTypes.isEmpty()) {
            Any::class.asTypeName()
        } else {
            arrayTypes.asIterable().first()
        }
        return if (nullable) typeName.asNullable() else typeName
    }

    private fun findJsonObjectType(jsonObject: JsonObject, className: String): TypeName {
        processJsonObject(jsonObject, className.capitalize()) // TODO need to de-dupe things!
        return ClassName.bestGuess(className.capitalize()).asNonNullable()
    }
}