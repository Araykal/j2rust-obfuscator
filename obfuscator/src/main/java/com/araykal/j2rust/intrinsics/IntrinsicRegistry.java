package com.araykal.j2rust.intrinsics;

import lombok.EqualsAndHashCode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IntrinsicRegistry {
    private static final int ANY_OPCODE = -1;
    private static final Map<Key, IntrinsicEmitter> EMITTERS = new ConcurrentHashMap<>();

    static {
        StringIntrinsics.register();
    }

    private IntrinsicRegistry() {
    }

    public static void register(String owner, String name, String descriptor, IntrinsicEmitter emitter) {
        register(owner, name, descriptor, ANY_OPCODE, emitter);
    }

    public static void register(String owner, String name, String descriptor, int opcode,
                                IntrinsicEmitter emitter) {
        if (owner == null || name == null || descriptor == null || emitter == null)
            throw new IllegalArgumentException("Intrinsic registration contains null");
        EMITTERS.put(new Key(owner, name, descriptor, opcode), emitter);
    }

    public static boolean emit(StringBuilder code, MethodInsnNode node, String exceptionPath) {
        IntrinsicEmitter emitter = EMITTERS.get(new Key(node.owner, node.name, node.desc, node.getOpcode()));
        if (emitter == null)
            emitter = EMITTERS.get(new Key(node.owner, node.name, node.desc, ANY_OPCODE));
        if (emitter == null) return false;
        emitter.emit(code, node, exceptionPath);
        return true;
    }

    @EqualsAndHashCode
    private static final class Key {
        private final String owner;
        private final String name;
        private final String descriptor;
        private final int opcode;

        private Key(String owner, String name, String descriptor, int opcode) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.opcode = opcode;
        }
    }
}
