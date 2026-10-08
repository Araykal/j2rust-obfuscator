package com.araykal.j2rust.compiletime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class LoaderUnpack {
    static {
        String osName = System.getProperty("os.name").toLowerCase();
        String platform = System.getProperty("os.arch").toLowerCase();

        String platformTypeName;
        switch (platform) {
            case "x86_64":
            case "amd64":
                platformTypeName = "x64";
                break;
            case "aarch64":
                platformTypeName = "arm64";
                break;
            case "arm":
                platformTypeName = "arm32";
                break;
            case "x86":
                platformTypeName = "x86";
                break;
            default:
                platformTypeName = "raw" + platform;
                break;
        }

        String osTypeName;
        String osToken;
        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            osToken = "linux";
            osTypeName = "so";
        } else if (osName.contains("win")) {
            osToken = "windows";
            osTypeName = "dll";
        } else if (osName.contains("mac")) {
            osToken = "macos";
            osTypeName = "dylib";
        } else {
            osToken = "raw" + osName;
            osTypeName = "bin";
        }

        String architectureFamily = platformTypeName.equals("x64") || platformTypeName.equals("x86") ? "x86" :
                platformTypeName.startsWith("arm") ? "arm" : "raw";
        String libraryName = String.format("rust-%s-%s_%s.%s", osToken, architectureFamily,
                platformTypeName, osTypeName);

        String className = LoaderUnpack.class.getName();
        int packageEnd = className.lastIndexOf('.');
        String packagePath = packageEnd < 0 ? "" : className.substring(0, packageEnd).replace('.', '/');
        String libFileName = String.format("/%s/%s", packagePath, libraryName);

        File libFile;
        try {
            libFile = File.createTempFile("lib", null);
            libFile.deleteOnExit();
            if (!libFile.exists()) {
                throw new IOException();
            }
        } catch (IOException iOException) {
            throw new UnsatisfiedLinkError("Failed to create temp file");
        }
        byte[] arrayOfByte = new byte[2048];
        try {
            InputStream inputStream = LoaderUnpack.class.getResourceAsStream(libFileName);
            if (inputStream == null) {
                throw new UnsatisfiedLinkError(String.format("Failed to open lib file: %s", libFileName));
            }
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(libFile);
                try {
                    int size;
                    while ((size = inputStream.read(arrayOfByte)) != -1) {
                        fileOutputStream.write(arrayOfByte, 0, size);
                    }
                    fileOutputStream.close();
                } catch (Throwable throwable) {
                    try {
                        fileOutputStream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                    throw throwable;
                }
                inputStream.close();
            } catch (Throwable throwable) {
                try {
                    inputStream.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
                throw throwable;
            }
        } catch (IOException exception) {
            throw new UnsatisfiedLinkError(String.format("Failed to copy file: %s", exception.getMessage()));
        }
        System.load(libFile.getAbsolutePath());
    }

    public static void ensureLoaded(String name, Class<?> clazz) {
        rustRegisterNatives(name, clazz);
    }

    private static native void rustRegisterNatives(String name, Class<?> clazz);
}
