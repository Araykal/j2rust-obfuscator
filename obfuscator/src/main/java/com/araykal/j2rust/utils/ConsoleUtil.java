package com.araykal.j2rust.utils;

public final class ConsoleUtil {
    private static final String RESET = "\u001B[0m";
    private static final String SKY = "\u001B[96m";
    private static final String GREEN = "\u001B[92m";
    private static final String YELLOW = "\u001B[93m";
    private static final String RED = "\u001B[91m";
    private static boolean enabled = !System.getenv().containsKey("NO_COLOR");

    private ConsoleUtil() {
    }

    public static void setColorEnabled(boolean value) {
        enabled = value;
    }

    public  static void banner(String version) {
        line(SKY, "      __   ___     ____                    __  ");
        line(SKY, "      / /  |__ \\   / __ \\  __  __   _____  / /_ ");
        line(SKY, " __  / /   __/ /  / /_/ / / / / /  / ___/ / __/");
        line(SKY, "/ /_/ /   / __/  / _, _/ / /_/ /  (__  ) / /_  ");
        line(SKY, "\\____/   /____/ /_/ |_|  \\,__/  /____/  \\__/  ");
        line(SKY, "                                                  " + version);
    }

    public  static void phase(String message) {
        line(RESET, "[J2Rust] " + message);
    }

    public static void detail(String message) {
        line(RESET, "    " + message);
    }

    public static void converted(String owner, String name, String mode) {
        line(GREEN, "[Rust] " + owner + "." + name + " -> " + mode);
    }

    public static void retained(String owner, String name, String reason) {
        line(YELLOW, "[Java] " + owner + "." + name + " retained: " + reason);
    }

    public static void summary(int converted, int retained, String jar, String library) {
        line(GREEN, "[J2Rust] Rust methods: " + converted + ", Java-retained: " + retained);
        line(SKY, "[J2Rust] JAR: " + jar + "; library: " + library);
    }

    public static void failure(String message) {
        line(RED, "[Error] " + message);
    }

    public static void line(String color, String message) {
        System.out.println(enabled ? color + message + RESET : message);
    }
}
