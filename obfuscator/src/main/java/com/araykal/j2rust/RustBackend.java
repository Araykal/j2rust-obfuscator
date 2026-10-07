package com.araykal.j2rust;

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
        process(input, output, useAnnotations, false);
    }

    public void process(Path input, Path output, boolean useAnnotations, boolean strict) throws Exception {
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
        ClassMethodFilter filter = new ClassMethodFilter(useAnnotations);
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
                if (entry.getName().startsWith("META-INF/versions/")) {
                    for (MethodNode method : clazz.methods) {
                        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                        skipped++;
                        coverage.add(clazz.name, method.name, method.desc, "java", "multi-release class variant");
                    }
                    entries.put(entry.getName(), bytes);
                    continue;
                }
                if (!filter.shouldProcess(clazz)) {
                    for (MethodNode method : clazz.methods)
                        if (RustMethodSelector.shouldProcess(method) || method.name.equals("<init>") || method.name.equals("<clinit>"))
                            coverage.add(clazz.name, method.name, method.desc, "excluded", "class filter");
                    entries.put(entry.getName(), bytes);
                    continue;
                }
                RustLegacySubroutineLowerer.lower(clazz);
                RustStringConcatLowerer.lower(clazz);
                lambdaHelpers.addAll(RustLambdaLowerer.lower(clazz, occupied));
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
                        coverage.add(clazz.name, method.name, method.desc, "java",
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
                            coverage.add(clazz.name, method.name, method.desc, "java",
                                    RustOpcodeSupport.unsupportedReason(method));
                            System.out.println("Kept Java: " + clazz.name + "." + method.name + method.desc +
                                    " — " + RustOpcodeSupport.unsupportedReason(method));
                        } else if (RustMethodSelector.shouldProcess(method))
                            coverage.add(clazz.name, method.name, method.desc, "excluded", "method filter");
                        continue;
                    }
                    coverage.add(clazz.name, method.name, method.desc, "rust", "");
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
                        coverage.add(clazz.name, constructor.name, constructor.desc, "java", "constructor prefix or body");
                        System.out.println("Kept Java: " + clazz.name + constructor.name +
                                constructor.desc + " — constructor prefix or body");
                        continue;
                    }
                    coverage.add(clazz.name, constructor.name, constructor.desc, "rust", "constructor bridge");
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
                        coverage.add(clazz.name, initializer.name, initializer.desc, "rust", "initializer bridge");
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
                        coverage.add(clazz.name, initializer.name, initializer.desc, "java",
                                RustOpcodeSupport.unsupportedReason(bridge));
                        System.out.println("Kept Java: " + clazz.name + ".<clinit>()V — " +
                                RustOpcodeSupport.unsupportedReason(bridge));
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
        ProcessBuilder build = new ProcessBuilder("cargo", "build", "--release", "--offline");
        build.directory(rustDir.toFile());
        build.redirectErrorStream(true);
        Process process = build.start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) log.write(chunk, 0, count);
        }
        if (process.waitFor() != 0) throw new IOException("Rust build failed:\n" + log.toString("UTF-8"));

        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String platform = arch.equals("amd64") || arch.equals("x86_64") ? "x64" :
                arch.equals("aarch64") ? "arm64" : arch.equals("x86") ? "x86" : arch;
        String extension = os.contains("win") ? "windows.dll" :
                os.contains("mac") ? "macos.dylib" : "linux.so";
        String filename = os.contains("win") ? LIBRARY + ".dll" :
                os.contains("mac") ? "lib" + LIBRARY + ".dylib" : "lib" + LIBRARY + ".so";
        Path compiled = rustDir.resolve("target").resolve("release").resolve(filename);
        if (!Files.isRegularFile(compiled)) throw new IOException("Missing compiled library: " + compiled);
        Files.copy(compiled, output.resolve(filename), StandardCopyOption.REPLACE_EXISTING);

        String loaderPath = loaderName + ".class";
        entries.put(loaderPath, loaderBytes(loaderName));
        entries.put("j2rust/" + platform + "-" + extension, Files.readAllBytes(compiled));
        Path result = output.resolve(input.getFileName());
        if (input.toAbsolutePath().normalize().equals(result.toAbsolutePath().normalize()))
            throw new IOException("Input and output JAR paths must differ");
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(result))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (signatureEntry(entry.getKey())) continue;
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        System.out.println("Rust methods: " + converted + ", kept as Java: " + skipped);
        System.out.println("JAR: " + result + "; library: " + output.resolve(filename));
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
