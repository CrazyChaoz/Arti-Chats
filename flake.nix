{
    inputs = {
        fenix.url = github:nix-community/fenix;
        naersk.url = github:nix-community/naersk;
        nixpkgs.url = github:nixos/nixpkgs/nixpkgs-unstable;
    };

    outputs = { self, fenix, naersk, nixpkgs }:
    let
        system = "x86_64-linux";
        pkgs = import nixpkgs { inherit system; config = { android_sdk.accept_license = true; }; };
        stringsUtil = pkgs.lib.strings;
        attributeUtils = pkgs.lib.attrsets;

        hostArchLlvm = "linux-x86_64";
        androidMinSdkApiLevel = "29";
        targetInfo = {
            armv7-linux-androideabi = {
                clang = "armv7a-linux-androideabi";
                gradle = "armeabi-v7a";
            };
            aarch64-linux-android = {
                clang = "aarch64-linux-android";
                gradle = "arm64-v8a";
            };
            i686-linux-android = {
                clang = "i686-linux-android";
                gradle = "x86";
            };
            x86_64-linux-android = {
                clang = "x86_64-linux-android";
                gradle = "x86_64";
            };
        };
        changeCaseSnakeUpper = str: ( stringsUtil.replaceStrings ["-"] ["_"] ( stringsUtil.toUpper str ) );
        clangTarget = target: targetInfo.${target}.clang;

        toolchain = with fenix.packages.${system};
        combine ([
            (stable.withComponents [
                "rustc"
                "cargo"
                "rustfmt"
            ])
            ] ++ (
            attributeUtils.mapAttrsToList ( target_name: _:
            (targets.${target_name}.stable).rust-std) targetInfo )
        );
        naersk-lib = pkgs.callPackage naersk {
            cargo = toolchain;
            rustc = toolchain;
        };

        ndk-bundle = (pkgs.androidenv.composeAndroidPackages {
            platformToolsVersion = "33.0.3";
            includeNDK = true;
            ndkVersions = ["25.2.9519653"];
            }).ndk-bundle;

        naerskBuildEmbedded = target: naersk-lib.buildPackage
        (
            let
                cargoPackage = "magic-chat-rust-lib";
            in {
                version = "0.1.0";
                root = ./.;
                src = ./.;
                cargoBuildOptions = lst: lst ++ [ "--package ${cargoPackage}"  ];
                cargoTestOptions = lst: lst ++ [ "--package ${cargoPackage}" ];
                #singleStep = true; # to catch non-determinism in dependencies
                overrideMain = _: {
                preBuild = ''
                    mkdir -p app/src/main/java/at/jku/ins/chat
                    '';
                 postBuild = ''
                  mkdir -p $out/app/src/main/java/at/jku/ins/chat
                    cp -r app/src/main/java/at/jku/ins/chat/* $out/app/src/main/java/at/jku/ins/chat
                    '';
                };
                nativeBuildInputs = with pkgs; [ ndk-bundle perl pkg-config ];
                #buildInputs = with pkgs; [ openssl ];

                copyLibs = true;
                CARGO_BUILD_TARGET = target;
                "RUST_BACKTRACE" = "1";
                "CC_${target}" =
                "${ndk-bundle}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/${hostArchLlvm}/bin/${ clangTarget target }${androidMinSdkApiLevel}-clang";
                "CARGO_TARGET_${ changeCaseSnakeUpper target }_LINKER" =
                "${ndk-bundle}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/${hostArchLlvm}/bin/${ clangTarget target }${androidMinSdkApiLevel}-clang";
            }
        );

        embeddedAllAndroid = attributeUtils.mapAttrs (target_name: _:  { embedded = naerskBuildEmbedded target_name; }) targetInfo;

    in {
        packages.${system}.default = pkgs.linkFarm "android-all-archs" (
            attributeUtils.mapAttrsToList (target_name: t_pkgs: { name = targetInfo.${target_name}.gradle; path = "${t_pkgs.embedded}"; }
            ) embeddedAllAndroid);

        devShells.${system}.default =
            let
              inherit pkgs;
            in pkgs.mkShell {
              packages = with pkgs; [
                cargo
                rustc
                ndk-bundle
              ];

              shellHook = ''
                echo "Welcome to the Rust development environment"
                echo "${ndk-bundle}"
              '';
            };
    };
}