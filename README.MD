# J2Rust

J2Rust converts supported Java bytecode in a JAR into Rust source, compiles it into a native library, and packages that library with a transformed JAR.

J2Rust does **not** guarantee that an application cannot be reverse engineered or cracked. Native code is another protection layer, not a complete security boundary. You may combine it with tools such as VMProtect or Themida to protect the compiled native library, but test the packaged application afterward because additional protection can affect JNI loading and compatibility.

## Requirements

- **Java:** JDK 8 or newer to build and run J2Rust. The target application's Java version must also be supported by the installed JDK and the bundled ASM version. Use the same or a newer JDK than the target application requires.
- **Rust:** Install Rust and Cargo using [rustup](https://rustup.rs/). The Rust target must match the operating system and CPU architecture on which the output JAR will run.
- **Native toolchain:** On Windows, install the MSVC C++ build tools for the Rust MSVC target. On Linux, install a C linker such as GCC or Clang. On macOS, install the Xcode Command Line Tools.
- **Cargo dependency:** The generator currently builds with `cargo --offline`; ensure `jni-sys` version `0.3.1` is already available in the local Cargo cache.

J2Rust builds one native library for the current platform. To distribute the application on another operating system or architecture, run the conversion there as well.

## Build

On Windows:

```powershell
.\gradlew.bat :obfuscator:shadowJar
```

On Linux or macOS:

```sh
./gradlew :obfuscator:shadowJar
```

The CLI is produced at `obfuscator/build/libs/obfuscator.jar`.

## Usage

```sh
java -jar obfuscator/build/libs/obfuscator.jar input.jar output
java -jar output/input.jar
```

### Config

Use `--config` when input, output, selection, and strictness should be kept in a readable file:

```sh
java -jar obfuscator/build/libs/obfuscator.jar --config config.toml
```

```toml
# J2Rust configuration 
# Input JAR path, relative to this file.
input = "test.jar"

# Output transformed JAR path, relative to this file.
output = "test/test-obf.jar"


[selection]
# Use @Native/@NotNative. This takes priority over include and exclude.
annotations = false

# Only convert matching classes or methods.
# Examples:
#   pack.Main       = all methods in one class
#   pack.*          = all classes directly in pack (not subpackages)
#   pack.**         = all classes in pack and its subpackages
#   pack.Main.*     = all methods in pack.Main
#   pack.Main#main  = one method
#   pack.Main#add(II)I = one method with descriptor.
include = []

# Skip matching classes or methods when include is empty.
# The same class, package, recursive-package, and method syntax as include is supported.
exclude = ["pack.tests.*","pack.Main#main"]


[options]
# Fail when selected methods remain Java.
strict = false

# Disable ANSI terminal colors.
no_color = false

# Build in a temporary directory, keep only the configured output JAR, then delete generated files.
clear = false


[build]
# Rust build tool: cargo or cargo-zigbuild.
tool = "cargo-zigbuild"

# Targets to compile when tool = cargo-zigbuild (cargo always uses host):
#   host
#   windows_x86_64
#   windows_aarch64
#   macos_x86_64
#   macos_aarch64
#   linux_x86_64
#   linux_aarch64.
# With cargo-zigbuild, Windows aliases use GNU/GNULVM targets.
targets = ["windows_x86_64","windows_aarch64","linux_x86_64","linux_aarch64"]
```

Supported target names are lowercase: `host`, `windows_x86_64`, `windows_aarch64`, `macos_x86_64`, `macos_aarch64`, `linux_x86_64`, and `linux_aarch64`. When `tool = "cargo"`, the `targets` list is ignored and only `host` is compiled.

For `cargo-zigbuild`, Windows targets use the GNU/GNULVM Rust targets automatically, avoiding MSVC linker flags. Missing Rust targets are installed with `rustup target add`; the regular `cargo` tool keeps the MSVC targets.

Use `--strict` to fail when a selected method must remain Java. The coverage report is still written before that failure:

```sh
java -jar obfuscator/build/libs/obfuscator.jar input.jar output --strict
```

Use `--annotations` to select methods using the project's `@Native` and `@NotNative` annotations:

```sh
java -jar obfuscator/build/libs/obfuscator.jar input.jar output --annotations
```

Supported bytecode includes common arithmetic, branches, switches, arrays, objects, fields, calls, exceptions, and legacy `JSR`/`RET` subroutines after lowering. Some `invokedynamic` sites and constant-pool entries require Java bootstrap bridges; these appear as retained Java methods in `coverage.json`. Multi-release class variants are preserved as Java bytecode rather than converted. Check the report and run the transformed application before distribution.

Generated loader and constructor bridge classes share a fresh four-character package under `j2rust/` for each conversion. This avoids fixed generated class names colliding with application classes.

## Tests

```sh
./gradlew :obfuscator:test
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`. If `test.jar` is present at the project root, the integration test also compares its original and transformed output.

## Speed Run
This tool uses tests from [huzpsb/JavaObfuscatorTest](https://github.com/huzpsb/JavaObfuscatorTest)

The following comparison was run on Windows x64 with Zulu OpenJDK 8u504 and Rust/Cargo 1.99.0

| Result | Original JAR | J2Rust output |
| --- | ---: | ---: |
| JAR size | 30,850 bytes | 239,318 bytes |
| `Calc` run 1 | 27 ms | 116 ms |
| `Calc` run 2 | 26 ms | 118 ms |
| `Calc` run 3 | 26 ms | 119 ms |
| Process exit status | 0 on all runs | 0 on all runs |

## Development Status

J2Rust is still under active development and is not yet a complete Java bytecode translator. Instruction coverage, bootstrap handling, and compatibility with diverse JARs remain incomplete, and the project has not undergone comprehensive stability testing across Java versions and platforms. Review `coverage.json` and test every transformed application before release. Contributions are welcome, especially additional bytecode lowering, regression fixtures, and platform-specific validation.

## Thanks

- [radioegor146/native-obfuscator](https://github.com/radioegor146/native-obfuscator)
- [jni-rs/jni-rs](https://github.com/jni-rs/jni-rs)
- [Megadash452/ez-jni-rs](https://github.com/Megadash452/ez-jni-rs)
- [rust-cross/cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild)
