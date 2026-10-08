package com.araykal.j2rust;

import com.araykal.j2rust.utils.ConsoleUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import com.araykal.j2rust.compiletime.LoaderUnpack;
import com.araykal.j2rust.model.MethodRegistration;
import com.araykal.j2rust.lowering.RustConstructorLowerer;
import com.araykal.j2rust.lowering.RustLambdaLowerer;
import com.araykal.j2rust.lowering.RustStringConcatLowerer;
import com.araykal.j2rust.lowering.RustInvokeDynamicLowerer;
import com.araykal.j2rust.lowering.RustLegacySubroutineLowerer;
import com.araykal.j2rust.report.CoverageReport;
import com.araykal.j2rust.build.BuildArtifact;
import com.araykal.j2rust.build.RustCompiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.security.SecureRandom;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RustBackend {
    private static final String LIBRARY = "native_library";
    private static final char[] GENERATED_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom GENERATED_RANDOM = new SecureRandom();

    public void process(Path input, Path output, boolean useAnnotations) throws Exception {
        process(input, output, useAnnotations, false, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), input.getFileName().toString(), false);
    }

    public void process(Path input, Path output, boolean useAnnotations, boolean strict) throws Exception {
        process(input, output, useAnnotations, strict, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), input.getFileName().toString(), false);
    }

    public void process(Path input, Path output, boolean useAnnotations, boolean strict,
                        List<String> include, List<String> exclude, String outputJarName) throws Exception {
        process(input, output, useAnnotations, strict, include, exclude, outputJarName, false);
    }

    public void process(Path input, Path output, boolean useAnnotations, boolean strict,
                        List<String> include, List<String> exclude, String outputJarName,
                        boolean clear) throws Exception {
        process(input, output, useAnnotations, strict, include, exclude, outputJarName, clear,
                "cargo", java.util.Collections.singletonList("host"));
    }

    public void process(Path input, Path output, boolean useAnnotations, boolean strict,
                        List<String> include, List<String> exclude, String outputJarName,
                        boolean clear, String buildTool, List<String> buildTargets) throws Exception {
        ConsoleUtil.phase("Preparing Rust runtime and JNI bridge");
        Files.createDirectories(output);
        CoverageReport coverage = new CoverageReport();
        Set<String> occupied = new HashSet<>();
        try (JarFile jar = new JarFile(input.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(jar.entries()))
                occupied.add(entry.getName());
        }
        Path rustDir = output.resolve("rust");
        Path sourceDir = rustDir.resolve("src");
        Files.createDirectories(sourceDir);
        String generatedPackage = randomGeneratedPackage(occupied);
        String loaderName = generatedPackage + "/RustLoader";
        String constructorHelperName = generatedPackage + "/ConstructorBridge";
        ClassNode constructorHelper = new ClassNode(Opcodes.ASM9);
        constructorHelper.version = Opcodes.V1_8;
        constructorHelper.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        constructorHelper.name = constructorHelperName;
        constructorHelper.superName = "java/lang/Object";
        StringBuilder source = new StringBuilder("use std::ffi::c_void;\nmod runtime;\nuse runtime::*;\n\n");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, List<MethodRegistration>> registrations = new LinkedHashMap<>();
        List<ClassNode> lambdaHelpers = new ArrayList<>();
        ClassMethodFilter filter = new ClassMethodFilter(useAnnotations, include, exclude);
        int converted = 0;
        int skipped = 0;

        try (JarFile jar = new JarFile(input.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(jar.entries())) {
                if (entry.isDirectory()) continue;
                byte[] bytes;
                try (InputStream stream = jar.getInputStream(entry)) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
                    bytes = buffer.toByteArray();
                }
                if (!entry.getName().endsWith(".class")) {
                    entries.put(entry.getName(), bytes);
                    continue;
                }
                ClassNode clazz = new ClassNode(Opcodes.ASM9);
                new ClassReader(bytes).accept(clazz, 0);
                ConsoleUtil.detail("Reading class " + clazz.name + " (" + clazz.methods.size() + " methods)");
                if (entry.getName().startsWith("META-INF/versions/")) {
                    ConsoleUtil.detail("Retaining multi-release variant " + entry.getName());
                    for (MethodNode method : clazz.methods) {
                        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                        skipped++;
                        coverage.add(clazz.name, method.name, method.desc, "java-retained", "multi-release class variant");
                    }
                    entries.put(entry.getName(), bytes);
                    continue;
                }
                if (!filter.shouldProcess(clazz)) {
                    ConsoleUtil.detail("Excluded by class filter: " + clazz.name);
                    for (MethodNode method : clazz.methods)
                        if (RustMethodSelector.shouldProcess(method) || method.name.equals("<init>") || method.name.equals("<clinit>"))
                            coverage.add(clazz.name, method.name, method.desc, "excluded", "class filter");
                    entries.put(entry.getName(), bytes);
                    continue;
                }
                ConsoleUtil.detail("Lowering legacy subroutines: " + clazz.name);
                RustLegacySubroutineLowerer.lower(clazz);
                ConsoleUtil.detail("Lowering string concatenation: " + clazz.name);
                RustStringConcatLowerer.lower(clazz);
                ConsoleUtil.detail("Lowering lambda factories: " + clazz.name);
                lambdaHelpers.addAll(RustLambdaLowerer.lower(clazz, occupied));
                ConsoleUtil.detail("Lowering invokedynamic and constants: " + clazz.name);
                List<MethodNode> dynamicBridges = RustInvokeDynamicLowerer.lower(clazz);
                Set<String> directMethods = new HashSet<>();
                for (MethodNode candidate : clazz.methods) {
                    if (!candidate.name.equals("<init>") && !candidate.name.equals("<clinit>") &&
                            filter.shouldProcess(clazz, candidate) && RustOpcodeSupport.supported(candidate) &&
                            (candidate.access & Opcodes.ACC_STATIC) != 0)
                        directMethods.add(candidate.name + candidate.desc);
                }
                boolean changed = false;
                MethodNode initializer = null;
                for (MethodNode method : clazz.methods) {
                    if (dynamicBridges.contains(method)) {
                        skipped++;
                        coverage.add(clazz.name, method.name, method.desc, "java-retained",
                                method.name.startsWith("j2rust$condy$") ?
                                        "JVM ConstantDynamic bootstrap bridge" :
                                        method.name.startsWith("j2rust$ldc$") ?
                                                "JVM method constant bridge" : "JVM invokedynamic bootstrap bridge");
                        continue;
                    }
                    if (method.name.equals("<clinit>")) {
                        initializer = method;
                        continue;
                    }
                    if (!filter.shouldProcess(clazz, method) || !RustOpcodeSupport.supported(method)) {
                        if (filter.shouldProcess(clazz, method) && RustMethodSelector.shouldProcess(method)) {
                            skipped++;
                            coverage.add(clazz.name, method.name, method.desc, "java-retained",
                                    RustOpcodeSupport.unsupportedReason(method));
                            ConsoleUtil.retained(clazz.name, method.name, RustOpcodeSupport.unsupportedReason(method));
                        } else if (RustMethodSelector.shouldProcess(method))
                            coverage.add(clazz.name, method.name, method.desc, "excluded", "method filter");
                        continue;
                    }
                    coverage.add(clazz.name, method.name, method.desc, RustExecutionMode.forMethod(method), "");
                    source.append(RustMethodEmitter.emit(clazz, method, directMethods));
                    registrations.computeIfAbsent(clazz.name, ignored -> new ArrayList<>())
                            .add(new MethodRegistration(clazz.name, method.name, method.desc,
                                    RustMethodEmitter.symbol(clazz.name, method)));
                    method.access |= Opcodes.ACC_NATIVE;
                    method.instructions.clear();
                    method.tryCatchBlocks.clear();
                    if (method.localVariables != null) method.localVariables.clear();
                    changed = true;
                    converted++;
                    ConsoleUtil.converted(clazz.name, method.name, RustExecutionMode.forMethod(method));
                }
                List<MethodNode> constructors = new ArrayList<>();
                for (MethodNode method : clazz.methods) {
                    if (method.name.equals("<init>") && filter.shouldProcess(clazz, method))
                        constructors.add(method);
                }
                for (MethodNode constructor : constructors) {
                    MethodNode bridge = RustConstructorLowerer.lower(clazz, constructor,
                            constructorHelperName, entries.size() * 100 + clazz.methods.indexOf(constructor));
                    if (bridge == null) {
                        skipped++;
                        coverage.add(clazz.name, constructor.name, constructor.desc, "java-retained", "constructor prefix or body");
                        ConsoleUtil.retained(clazz.name, constructor.name, "constructor prefix or body");
                        continue;
                    }
                    coverage.add(clazz.name, constructor.name, constructor.desc, "rust-jni", "constructor bridge");
                    source.append(RustMethodEmitter.emit(constructorHelper, bridge));
                    registrations.computeIfAbsent(constructorHelperName, ignored -> new ArrayList<>())
                            .add(new MethodRegistration(constructorHelperName, bridge.name, bridge.desc,
                                    RustMethodEmitter.symbol(constructorHelperName, bridge)));
                    bridge.access |= Opcodes.ACC_NATIVE;
                    bridge.instructions.clear();
                    bridge.tryCatchBlocks.clear();
                    constructorHelper.methods.add(bridge);
                    changed = true;
                    converted++;
                    ConsoleUtil.converted(clazz.name, constructor.name, "rust-jni");
                }
                if (initializer != null && filter.shouldProcess(clazz, initializer)) {
                    MethodNode bridge = new MethodNode(Opcodes.ASM9,
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            "j2rust$clinit", "()V", null, null);
                    bridge.instructions = initializer.instructions;
                    bridge.tryCatchBlocks = initializer.tryCatchBlocks;
                    bridge.localVariables = initializer.localVariables;
                    bridge.maxLocals = initializer.maxLocals;
                    bridge.maxStack = initializer.maxStack;
                    if (RustOpcodeSupport.supported(bridge)) {
                        coverage.add(clazz.name, initializer.name, initializer.desc, "rust-jni", "initializer bridge");
                        source.append(RustMethodEmitter.emit(clazz, bridge));
                        registrations.computeIfAbsent(clazz.name, ignored -> new ArrayList<>())
                                .add(new MethodRegistration(clazz.name, bridge.name, bridge.desc,
                                        RustMethodEmitter.symbol(clazz.name, bridge)));
                        bridge.access |= Opcodes.ACC_NATIVE;
                        bridge.instructions.clear();
                        bridge.tryCatchBlocks.clear();
                        if (bridge.localVariables != null) bridge.localVariables.clear();
                        clazz.methods.add(bridge);
                        initializer.instructions = new InsnList();
                        initializer.tryCatchBlocks = new ArrayList<>();
                        initializer.localVariables = null;
                        initializer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                clazz.name, bridge.name, "()V", false));
                        initializer.instructions.add(new InsnNode(Opcodes.RETURN));
                        changed = true;
                        converted++;
                    } else {
                        skipped++;
                        coverage.add(clazz.name, initializer.name, initializer.desc, "java-retained",
                                RustOpcodeSupport.unsupportedReason(bridge));
                        ConsoleUtil.retained(clazz.name, "<clinit>", RustOpcodeSupport.unsupportedReason(bridge));
                    }
                }
                if (changed) {
                    if (registrations.containsKey(clazz.name)) {
                        if (initializer == null) {
                            initializer = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                            initializer.instructions.add(new InsnNode(Opcodes.RETURN));
                            clazz.methods.add(initializer);
                        }
                        InsnList load = new InsnList();
                        load.add(new LdcInsnNode(clazz.name));
                        load.add(new LdcInsnNode(Type.getObjectType(clazz.name)));
                        load.add(new MethodInsnNode(Opcodes.INVOKESTATIC, loaderName, "ensureLoaded",
                                "(Ljava/lang/String;Ljava/lang/Class;)V", false));
                        initializer.instructions.insert(load);
                    }
                    ClassMethodFilter.cleanAnnotations(clazz);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    clazz.accept(writer);
                    bytes = writer.toByteArray();
                }
                entries.put(entry.getName(), bytes);
            }
        }
        ConsoleUtil.phase("Writing coverage.json");
        coverage.write(output.resolve("coverage.json"));
        if (strict && skipped != 0)
            throw new IOException("Strict mode: " + skipped + " selected methods remain Java; see " + output.resolve("coverage.json"));
        if (converted == 0) throw new IllegalArgumentException("No supported methods found; input JAR was not modified");

        for (ClassNode helper : lambdaHelpers) {
            for (MethodNode method : helper.methods) {
                if (method.name.equals("<init>")) continue;
                if (!RustOpcodeSupport.supported(method))
                    throw new IOException("Unsupported generated lambda method: " + helper.name + "." + method.name);
                source.append(RustMethodEmitter.emit(helper, method));
                registrations.computeIfAbsent(helper.name, ignored -> new ArrayList<>())
                        .add(new MethodRegistration(helper.name, method.name, method.desc,
                                RustMethodEmitter.symbol(helper.name, method)));
                method.access |= Opcodes.ACC_NATIVE;
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                converted++;
            }
            MethodNode initializer = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                    "<clinit>", "()V", null, null);
            initializer.instructions.add(new LdcInsnNode(helper.name));
            initializer.instructions.add(new LdcInsnNode(Type.getObjectType(helper.name)));
            initializer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, loaderName, "ensureLoaded",
                    "(Ljava/lang/String;Ljava/lang/Class;)V", false));
            initializer.instructions.add(new InsnNode(Opcodes.RETURN));
            helper.methods.add(initializer);
            ClassWriter helperWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            helper.accept(helperWriter);
            entries.put(helper.name + ".class", helperWriter.toByteArray());
        }

        if (!constructorHelper.methods.isEmpty()) {
            MethodNode initializer = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                    "<clinit>", "()V", null, null);
            initializer.instructions.add(new LdcInsnNode(constructorHelperName));
            initializer.instructions.add(new LdcInsnNode(Type.getObjectType(constructorHelperName)));
            initializer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, loaderName, "ensureLoaded",
                    "(Ljava/lang/String;Ljava/lang/Class;)V", false));
            initializer.instructions.add(new InsnNode(Opcodes.RETURN));
            constructorHelper.methods.add(initializer);
            ClassWriter helperWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            constructorHelper.accept(helperWriter);
            entries.put(constructorHelperName + ".class", helperWriter.toByteArray());
        }

        source.append(RustRegistrationEmitter.emit(registrations, loaderName));
        ConsoleUtil.phase("Generating Rust project");
        Files.write(rustDir.resolve("Cargo.toml"), (
                "[package]\nname = \"" + LIBRARY + "\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n" +
                "[lib]\ncrate-type = [\"cdylib\"]\n\n[dependencies]\njni-sys = \"0.3.1\"\n").getBytes(StandardCharsets.UTF_8));
        Files.write(sourceDir.resolve("lib.rs"), source.toString().getBytes(StandardCharsets.UTF_8));
        Path runtimeDir = sourceDir.resolve("runtime");
        Files.createDirectories(runtimeDir);
        for (String module : new String[]{"mod", "value", "stack", "jni", "arrays", "methods", "fields", "refs", "strings"}) {
            try (InputStream runtime = RustBackend.class.getResourceAsStream("/sources/rust_runtime/" + module + ".rs")) {
                if (runtime == null) throw new IOException("Rust runtime module missing: " + module);
                Files.copy(runtime, runtimeDir.resolve(module + ".rs"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        List<BuildArtifact> artifacts = new RustCompiler().compile(rustDir, buildTool, buildTargets);
        for (BuildArtifact artifact : artifacts) {
            Files.copy(artifact.path(), output.resolve(artifact.filename()), StandardCopyOption.REPLACE_EXISTING);
            entries.put(generatedPackage + "/" + artifact.filename(), Files.readAllBytes(artifact.path()));
        }

        String loaderPath = loaderName + ".class";
        entries.put(loaderPath, loaderBytes(loaderName));
        Path result = output.resolve(outputJarName);
        if (input.toAbsolutePath().normalize().equals(result.toAbsolutePath().normalize()))
            throw new IOException("Input and output JAR paths must differ");
        ConsoleUtil.phase("Packaging transformed JAR");
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(result))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (signatureEntry(entry.getKey())) continue;
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        ConsoleUtil.summary(converted, skipped, result.toString(), output.resolve("native libraries").toString());
    }

    private byte[] loaderBytes(String name) throws IOException {
        String original = Type.getInternalName(LoaderUnpack.class);
        try (InputStream stream = LoaderUnpack.class.getResourceAsStream("LoaderUnpack.class")) {
            if (stream == null) throw new IOException("LoaderUnpack.class missing");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(stream).accept(new ClassRemapper(node, new Remapper() {
                @Override
                public String map(String internalName) {
                    return internalName.equals(original) ? name : internalName;
                }
            }), 0);
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        }
    }

    private static String randomGeneratedPackage(Set<String> occupied) {
        while (true) {
            StringBuilder packageName = new StringBuilder("j2rust/");
            for (int index = 0; index < 4; index++)
                packageName.append(GENERATED_ALPHABET[GENERATED_RANDOM.nextInt(GENERATED_ALPHABET.length)]);
            String prefix = packageName.toString();
            if (!occupied.contains(prefix + "/RustLoader.class") &&
                    !occupied.contains(prefix + "/ConstructorBridge.class")) return prefix;
        }
    }

    private static boolean signatureEntry(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String filename = upper.substring("META-INF/".length());
        if (filename.indexOf('/') >= 0) return false;
        return filename.endsWith(".SF") || filename.endsWith(".RSA") ||
                filename.endsWith(".DSA") || filename.endsWith(".EC") ||
                filename.startsWith("SIG-") || filename.equals("INDEX.LIST");
    }

}
