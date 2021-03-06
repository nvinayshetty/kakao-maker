package com.aafanasev.kakaomaker.tasks

import com.aafanasev.kakaomaker.KakaoMakerPlugin
import com.aafanasev.kakaomaker.KakaoMakerPluginExtension
import com.aafanasev.kakaomaker.util.KakaoTypeProvider
import com.aafanasev.kakaomaker.util.id
import com.aafanasev.kakaomaker.util.isIncludeTag
import com.aafanasev.kakaomaker.util.isMergeTag
import com.aafanasev.kakaomaker.util.kakaoIgnore
import com.aafanasev.kakaomaker.util.kakaoScreenName
import com.aafanasev.kakaomaker.util.layout
import com.aafanasev.kakaomaker.util.simpleLazy
import com.aafanasev.kakaomaker.util.viewIdToName
import com.agoda.kakao.screen.Screen
import com.android.build.gradle.BaseExtension
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.w3c.dom.Element
import java.io.File
import java.util.*
import javax.annotation.Generated
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

open class GenerateKakaoScreensTask : DefaultTask() {

    private val androidExtension by simpleLazy {
        project.extensions["android"] as BaseExtension
    }
    private val kakaoMakerExtension by simpleLazy {
        project.extensions[KakaoMakerPlugin.EXTENSION_NAME] as KakaoMakerPluginExtension
    }

    private val layoutFiles by simpleLazy {
        androidExtension.sourceSets["main"]
                .res
                .sourceFiles
                .filter { it.path.contains("/layout/") }
    }

    private val generatedAnnotation by simpleLazy {
        AnnotationSpec.builder(Generated::class.java)
                .addMember("value = [%S]", "Kakao Maker")
                .addMember("comments = %S", "https://github.com/aafanasev/kakao-maker")
                .addMember("date = %S", Date())
                .build()
    }

    @TaskAction
    @Suppress("unused")
    fun generateKakaoScreens() {
        ensureOutputDir()

        val parser = createXmlParser()

        layoutFiles.forEach loop@{
            val document = parser.parse(it)

            if (document.documentElement.isMergeTag) {
                return@loop
            }

            val screenName = document.documentElement.kakaoScreenName
            if (screenName.isNotEmpty()) {
                log("Generating $screenName...")

                val classBuilder = TypeSpec
                        .classBuilder(screenName)
                        .addAnnotation(generatedAnnotation)
                        .superclass(Screen::class.asClassName().parameterizedBy(ClassName.bestGuess(screenName)))

                addProperties(classBuilder, document.documentElement)

                val file = FileSpec
                        .builder(kakaoMakerExtension.packageName!!, "$screenName.kt")
                        .addImport(kakaoMakerExtension.applicationId!!, "R")
                        .addType(classBuilder.build())
                        .build()

                if (kakaoMakerExtension.debug) {
                    log("-- $screenName BEGIN --")
                    file.writeTo(System.out)
                    log("-- $screenName END --")
                }

                File(kakaoMakerExtension.outputDir, file.name).writeText(file.toString())
            }

            parser.reset()
        }
    }

    private fun addProperties(classBuilder: TypeSpec.Builder, element: Element) {
        if (element.kakaoIgnore) {
            return
        }

        if (element.isIncludeTag) {
            parseIncludedLayout(classBuilder, element)
            return
        }

        val elementId = element.id
        if (elementId.isNotEmpty()) {
            val propertyName = viewIdToName(elementId)
            val propertyType = KakaoTypeProvider.getType(element.tagName)

            val property = PropertySpec
                    .builder(propertyName, propertyType)
                    .initializer("%L { withId(R.id.%L) } ", propertyType.simpleName, elementId)
                    .build()

            classBuilder.addProperty(property)
        }

        if (element.hasChildNodes()) {
            for (index in 0 until element.childNodes.length) {
                val node = element.childNodes.item(index)

                if (node.nodeType == Element.ELEMENT_NODE) {
                    addProperties(classBuilder, node as Element)
                }
            }
        }
    }

    private fun parseIncludedLayout(classBuilder: TypeSpec.Builder, element: Element) {
        val parser = createXmlParser()

        val includedFile = layoutFiles
                .find {
                    it.nameWithoutExtension == element.layout
                }

        val document = parser.parse(includedFile)

        val screenName = document.documentElement.kakaoScreenName
        if (screenName.isNotEmpty()) {
            val elementId = element.id
            val propertyName = if (elementId.isNotEmpty()) {
                viewIdToName(elementId)
            } else {
                screenName.decapitalize()
            }

            val property = PropertySpec
                    .builder(propertyName, ClassName.bestGuess(screenName))
                    .initializer("%L()", screenName)
                    .build()

            classBuilder.addProperty(property)
        } else {
            addProperties(classBuilder, document.documentElement)
        }

        parser.reset()
    }

    private fun ensureOutputDir() {
        val outputDir: File = kakaoMakerExtension.outputDir ?: throw GradleException("Output path is not specified")

        when {
            outputDir.exists() -> log("Output directory already exists")
            outputDir.mkdirs() -> log("Output directory successfully created")
            else -> throw GradleException("Cannot create output directory: ${outputDir.path}")
        }
    }

    private fun createXmlParser(): DocumentBuilder {
        return DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                }
                .newDocumentBuilder()
    }

    private fun log(msg: String) {
        if (kakaoMakerExtension.debug) {
            println("[$name] $msg")
        }
    }

}