package com.thoughtworks

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.utils.SourceRoot
import com.google.gson.Gson
import com.thoughtworks.model.ResultItem
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


private val projectPath: Path = Paths.get("/tmp/circuit_breaker_detector/target")
val symbolSolver = CombinedTypeSolver().also { combinedTypeSolver ->
    combinedTypeSolver.add(ReflectionTypeSolver())
    combinedTypeSolver.add(JavaParserTypeSolver(projectPath))
}.run { JavaSymbolSolver(this) }

fun main() {
    val parseConfiguration = ParserConfiguration().also {
        it.setSymbolResolver(
            symbolSolver
        )
    }
    val compilationUnits =
        SourceRoot(projectPath, parseConfiguration).run { this.tryToParse() }.map { it.result.value!! }

    for (compilationUnit in compilationUnits) {
        cache.putAll(scanCircuitBreakerRootsAtCompilationUnit(compilationUnit))
    }
    val results = compilationUnits.mapNotNull { it.primaryType.value }.asSequence()
        .filter { it.annotations.any { ann -> ann.nameAsString.contains("Controller") } }
        .flatMap { it.methods.asSequence() }.map { it to resolveMethod(it, emptySet()) }
        .filter { it.second.isNotEmpty() }.map { (k, v) -> getEndpoint(k) to v }.map {
            ResultItem(it.first, it.second)
        }.toList()
    val resultJson = Gson().toJson(results)
    File("./result.json").writeText(resultJson)
    projectPath.toFile().deleteRecursively()
}

fun getEndpoint(methodDeclaration: MethodDeclaration): String {
    val annotationExpr = methodDeclaration.annotations.find { it.nameAsString.contains("Mapping") }!!
    val uri = if (annotationExpr.isSingleMemberAnnotationExpr) {
        annotationExpr.asSingleMemberAnnotationExpr().memberValue
    } else {
        annotationExpr.asNormalAnnotationExpr().pairs.find { it.nameAsString == "path" }!!.value
    }

    val prefix = methodDeclaration.findAncestor(TypeDeclaration::class.java).value!!.annotations.find {
        it.nameAsString.contains("Mapping")
    }?.asSingleMemberAnnotationExpr()?.memberValue
    return if (prefix == null) {
        uri.toString().trim('"')
    } else {
        prefix.toString().trim('"') + uri.toString().trim('"')
    }
}

val cache: MutableMap<MethodDeclaration, Set<String>> =
    emptyMap<MethodDeclaration, Set<String>>().toMutableMap()

fun resolveMethod(
    methodDeclaration: MethodDeclaration,
    handling: Set<MethodDeclaration>
): Set<String> {
    val thisHandling = handling + setOf(methodDeclaration)
    fun Sequence<Result<ResolvedMethodDeclaration>>.resolveAll(handlingStack: Set<MethodDeclaration>): Set<String> {
        return filter { it.isSuccess }.map { it.getOrThrow() }.mapNotNull { it.toAst().value }
            .filterNot { it in handlingStack }.map {
                resolveMethod(
                    it,
                    thisHandling
                )
            }.fold(emptySet()) { acc: Set<String>, set: Set<String> -> acc + set }
    }

    return cache[methodDeclaration] ?: (methodDeclaration.findAll(MethodCallExpr::class.java).asSequence()
        .map { it.runCatching { symbolSolver.resolveDeclaration(it, ResolvedMethodDeclaration::class.java) } }
        .resolveAll(thisHandling) +
            methodDeclaration.findAll(MethodReferenceExpr::class.java).asSequence()
                .map { it.runCatching { symbolSolver.resolveDeclaration(it, ResolvedMethodDeclaration::class.java) } }
                .resolveAll(thisHandling))
        .also { cache[methodDeclaration] = it }

}

fun scanCircuitBreakerRootsAtCompilationUnit(compilationUnit: CompilationUnit): Map<MethodDeclaration, Set<String>> {
    val typeDeclaration = compilationUnit.primaryType.value
    return typeDeclaration?.annotations?.asSequence()?.getCircuitBreakerAnnotation()?.getCircuitBreakerType()
        ?.let { crName ->
            typeDeclaration.methods.filter { methodDeclaration -> methodDeclaration.isPublic }
                .associateWith { setOf(crName) }
        } ?: typeDeclaration?.methods?.map {
        it to (it.annotations.asSequence().getCircuitBreakerAnnotation()?.getCircuitBreakerType()
            ?.let { crName -> setOf(crName) } ?: emptySet())
    }?.filter { it.second.isNotEmpty() }?.toMap() ?: emptyMap()
}

val <T> Optional<T>.value: T?
    get() = orElse(null)

fun Sequence<AnnotationExpr>.getCircuitBreakerAnnotation() =
    find { it.nameAsString == "CircuitBreaker" }?.asNormalAnnotationExpr()

typealias CircuitBreakerAnnotation = NormalAnnotationExpr

fun CircuitBreakerAnnotation.getCircuitBreakerType() =
    pairs?.find { it.nameAsString == "name" }?.value?.toString()?.trim('"')
