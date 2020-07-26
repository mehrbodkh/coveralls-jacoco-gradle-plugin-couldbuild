package org.gradle.plugin.coveralls.jacoco

import org.apache.log4j.LogManager
import org.apache.log4j.Logger
import org.dom4j.io.SAXReader
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

data class SourceReport(val name: String, val source_digest: String, val coverage: List<Int?>)

class SourceReportParser {
    companion object {
        private val logger: Logger by lazy { LogManager.getLogger(CoverallsReporter::class.java) }

        private fun read(reportPath: String, rootPackage: String?): Map<String, Map<Int, Int>> {
            val reader = SAXReader()
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

            val document = reader.read(File(reportPath))
            val root = document.rootElement

            val rootPackagePath = rootPackage?.replace(".", "/")

            val fullCoverage = mutableMapOf<String, MutableMap<Int, Int>>()
            root.elements("package").forEach { pkg ->
                val pkgName = pkg.attributeValue("name")
                val path = rootPackagePath?.let {
                    pkgName.replaceFirst("^$it".toRegex(), "")
                } ?: pkgName

                pkg.elements("sourcefile").forEach { sf ->
                    val sfName = sf.attributeValue("name")
                    val key = "$path/$sfName"

                    if (fullCoverage[key] == null) {
                        fullCoverage[key] = mutableMapOf()
                    }

                    sf.elements("line").forEach { line ->
                        val lineIndex = line.attributeValue("nr").toInt() - 1

                        // jacoco doesn't count hits
                        fullCoverage.getValue(key)[lineIndex] = if (line.attributeValue("ci").toInt() > 0) 1 else 0
                    }
                }
            }

            logger.info("parsed coverage at $reportPath")

            return fullCoverage.mapValues { (_, v) -> v.toMap() }.toMap()
        }

        private fun File.md5(): String {
            val md = MessageDigest.getInstance("MD5")
            return BigInteger(1, md.digest(readBytes())).toString(16).padStart(32, '0')
        }

        fun parse(project: Project): List<SourceReport> {
            val pluginExtension = project.extensions.getByName("coverallsJacoco") as CoverallsJacocoPluginExtension
            val kotlinExtension = project.extensions.getByName("kotlin") as KotlinProjectExtension

            val sourceDirs = kotlinExtension.sourceSets.getByName("main").kotlin.srcDirs.filterNotNull()

            return read(pluginExtension.reportPath, pluginExtension.rootPackage)
                    .mapNotNull { (filename, cov) ->
                        sourceDirs.find {
                            File(it, filename).let { f ->
                                f.exists().also { exists ->
                                    if (!exists) logger.debug("${f.absolutePath} does not exist, skipping")
                                }
                            }
                        }?.let { dir ->
                            val f = File(dir, filename)
                            val lines = f.readLines()
                            val lineHits = arrayOfNulls<Int>(lines.size)

                            cov.forEach { (line, hits) -> lineHits[line] = hits }

                            val relPath = File(project.projectDir.absolutePath).toURI().relativize(f.toURI()).toString()
                            SourceReport(relPath, f.md5(), lineHits.toList())
                        }
                    }
        }
    }
}