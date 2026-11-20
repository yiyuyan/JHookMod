package cn.ksmcbrigade.jhookmod.utils.agent;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.*;

public class AgentUtils {

    public static File createAgent() throws IOException {
        return createAgent("JHookJavaAgent");
    }

    public static File createAgent(String prefix) throws IOException {
        String suffix = RandomStringUtils.random(6, true, true);
        String simpleClassName = prefix + "_" + suffix;
        String fullClassName = "generated." + simpleClassName;
        String classPath = "generated/" + simpleClassName + ".class";

        byte[] classBytes = generateClassBytes(fullClassName);

        Path tmp = Path.of(System.getProperty("user.dir")).resolve("tmp_agents");
        Files.createDirectories(tmp);
        File jarFile = tmp.resolve(
                RandomStringUtils.randomNumeric(12) + ".jar").toFile();

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
            Manifest manifest = new Manifest();
            Attributes attrs = manifest.getMainAttributes();
            attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attrs.put(new Attributes.Name("Premain-Class"), fullClassName);
            attrs.put(new Attributes.Name("Agent-Class"), fullClassName);
            attrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");
            attrs.put(new Attributes.Name("Can-Redefine-Classes"), "true");

            JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
            jos.putNextEntry(manifestEntry);
            manifest.write(jos);
            jos.closeEntry();

            JarEntry classEntry = new JarEntry(classPath);
            jos.putNextEntry(classEntry);
            jos.write(classBytes);
            jos.closeEntry();
        }

        return jarFile;
    }

    public static byte[] generateClassBytes(String fullClassName) {
        String internalName = fullClassName.replace('.', '/');

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // premain(String, Instrumentation)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "premain",
                "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, "agentmain",
                "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // agentmain(String, Instrumentation)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "agentmain",
                "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V",
                null, null);
        mv.visitCode();
        // System.getProperties().put("___inst___", inst);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn("___inst___");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    public static Instrumentation injectTmpAgent() throws IOException {
        File file = createAgent();
        UnsafeUtils.loadAgent(file.getAbsolutePath());
        file.deleteOnExit();
        FileUtils.forceDeleteOnExit(file);
        return getInst();
    }

    public static Instrumentation getInst(){
        return (Instrumentation) System.getProperties().getOrDefault("___inst___",null);
    }
}