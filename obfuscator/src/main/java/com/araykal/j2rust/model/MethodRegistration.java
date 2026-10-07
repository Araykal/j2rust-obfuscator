package com.araykal.j2rust.model;

public final class MethodRegistration {
    private final String owner;
    private final String name;
    private final String descriptor;
    private final String symbol;

    public MethodRegistration(String owner, String name, String descriptor, String symbol) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        this.symbol = symbol;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public String getSymbol() {
        return symbol;
    }
}
