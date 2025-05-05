{
  inputs = {
    fenix.url = "github:nix-community/fenix";
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    crane.url = "github:ipetkov/crane";
  };

  outputs = { self, fenix, nixpkgs, crane, ... }:
    let
      system = "x86_64-linux";


      pkgs = import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfree = true;
        };
      };

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
      changeCaseSnakeUpper = str: (stringsUtil.replaceStrings [ "-" ] [ "_" ] (stringsUtil.toUpper str));
      clangTarget = target: targetInfo.${target}.clang;

      toolchain = with fenix.packages.${system};
        combine ([
          (stable.withComponents [
            "rustc"
            "cargo"
            "rustfmt"
          ])
        ] ++ (
          attributeUtils.mapAttrsToList
            (target_name: _:
              (targets.${target_name}.stable).rust-std)
            targetInfo)
        );

      ndk-bundle = (pkgs.androidenv.composeAndroidPackages {
        platformToolsVersion = "33.0.3";
        includeNDK = true;
        ndkVersions = [ "25.2.9519653" ];
      }).ndk-bundle;

      craneLib = (crane.mkLib pkgs).overrideToolchain toolchain;

      naerskBuildEmbedded = target: craneLib.buildPackage {
        src = craneLib.cleanCargoSource ./.;
        version = "0.1.0";
        #singleStep = true; # to catch non-determinism in dependencies
        doCheck = false;
        nativeBuildInputs = with pkgs; [
          ndk-bundle
          perl
          pkg-config
          rustPlatform.bindgenHook
          clang
          libclang
        ];
        buildInputs = with pkgs; [ openssl ];

        #copyLibs = true;
        CARGO_BUILD_TARGET = target;
        "RUST_BACKTRACE" = "1";
        "RUST_LOG" = "debug";
        "CC_${target}" =
          "${ndk-bundle}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/${hostArchLlvm}/bin/${ clangTarget target }${androidMinSdkApiLevel}-clang";
        "CARGO_TARGET_${ changeCaseSnakeUpper target }_LINKER" =
          "${ndk-bundle}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/${hostArchLlvm}/bin/${ clangTarget target }${androidMinSdkApiLevel}-clang";
      };

      embeddedAllAndroid = attributeUtils.mapAttrs (target_name: _: { embedded = naerskBuildEmbedded target_name; }) targetInfo;

      androidAllArchs = pkgs.linkFarm "android-all-archs" (
        attributeUtils.mapAttrsToList
          (target_name: t_pkgs: {
            name = targetInfo.${target_name}.gradle;
            path = "${t_pkgs.embedded}/lib";
          }
          )
          embeddedAllAndroid);

      kotlin-ffi =
        craneLib.buildPackage {
          src = craneLib.cleanCargoSource ./.;
          doCheck = true;
          cargoTestCommand = "cargo run --features=uniffi/cli --bin uniffi-bindgen generate --library target/release/libtor_chat.so --language kotlin --out-dir $out";
          cargoExtraArgs = "";
          nativeBuildInputs = with pkgs; [ ndk-bundle perl pkg-config rustPlatform.bindgenHook ];
        };

    in
    {
      packages.${system} = {
        default = androidAllArchs;
        ffi-files = kotlin-ffi;
      };

      devShells.${system}.default =
        let
          inherit pkgs;
        in
        pkgs.mkShell {
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
