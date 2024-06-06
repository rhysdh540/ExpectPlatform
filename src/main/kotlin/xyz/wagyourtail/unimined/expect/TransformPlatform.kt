package xyz.wagyourtail.unimined.expect

import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.nio.file.Path
import kotlin.io.path.*


class TransformPlatform(val platformName: String) {

    @OptIn(ExperimentalPathApi::class)
    fun transform(inputRoot: Path, outputRoot: Path) {
        for (path in inputRoot.walk()) {
            if (path.isDirectory()) {
                outputRoot.resolve(inputRoot.relativize(path).toString()).createDirectories()
                continue
            }

            if (path.extension != "class") {
                outputRoot.resolve(inputRoot.relativize(path).toString()).writeBytes(path.readBytes())
                continue
            }

            val output = outputRoot.resolve(inputRoot.relativize(path).toString())
            val reader = ClassReader(path.readBytes())

            val classNode = ClassNode().also { reader.accept(it, 0) }
            val epMethods = mutableMapOf<MethodNode, AnnotationNode>()
            val poMethods = mutableMapOf<MethodNode, AnnotationNode>()
            classNode.methods.forEach {
                it.invisibleAnnotations?.forEach { annotation ->
                    if (annotation.desc == "Lxyz/wagyourtail/unimined/expect/annotation/ExpectPlatform;") {
                        epMethods[it] = annotation
                    } else if (annotation.desc == "Lxyz/wagyourtail/unimined/expect/annotation/PlatformOnly;") {
                        poMethods[it] = annotation
                    }
                }
            }

            epMethods.forEach { (method, annotation) -> expectPlatform(method, classNode, annotation) }
            poMethods.forEach { (method, annotation) -> platformOnly(method, classNode, annotation) }

            val writer = ClassWriter(0).also { classNode.accept(it) }
            output.createParentDirectories()
            output.writeBytes(writer.toByteArray())
        }
    }

    private fun expectPlatform(method: MethodNode, classNode: ClassNode, annotation: AnnotationNode) {
        if ((method.access and Opcodes.ACC_PUBLIC) == 0 || (method.access and Opcodes.ACC_STATIC) == 0) {
            error("Method annotated with @ExpectPlatform must be public static: ${classNode.name.replace('/', '.')}.${method.name}")
        }

        @Suppress("UNCHECKED_CAST")
        val platforms = annotation.values[1] as List<AnnotationNode>

        var platformClass: String? = null

        for (platform in platforms) {
            val name = platform.values[1] as String
            val clazz = platform.values[3] as String
            if(name == platformName) {
                platformClass = clazz
                break
            }
        }

        if(platformClass == null) {
            val packag = classNode.name.substringBeforeLast('/')
            val name = classNode.name.substringAfterLast('/')
            platformClass = "$packag/$platformName/${name}Impl"
        }

        method.instructions.clear()
        val type: Type = Type.getMethodType(method.desc)

        var stackIndex = 0
        for (argumentType in type.argumentTypes) {
            method.instructions.add(VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), stackIndex))
            stackIndex += argumentType.size
        }

        method.instructions.add(
            MethodInsnNode(Opcodes.INVOKESTATIC, platformClass, method.name, method.desc)
        )
        method.instructions.add(InsnNode(type.returnType.getOpcode(Opcodes.IRETURN)))

        method.maxStack = -1
    }

    private fun platformOnly(method: MethodNode, classNode: ClassNode, annotation: AnnotationNode) {
        @Suppress("UNCHECKED_CAST")
        val platforms = annotation.values[1] as List<String>

        if(platformName !in platforms) {
            classNode.methods.remove(method)
        }
    }

}