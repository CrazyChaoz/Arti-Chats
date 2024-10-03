use flapigen::{JavaConfig, LanguageConfig};
use std::{env, path::Path};

#[cfg(target_os = "android")]
fn main() {
    env_logger::init();
    let out_dir = env::var("OUT_DIR").unwrap();
    let in_src = Path::new("src").join("java_glue.rs.in");
    let out_src = Path::new(&out_dir).join("java_glue.rs");
    //ANCHOR: config
    let swig_gen = flapigen::Generator::new(LanguageConfig::JavaConfig(
        JavaConfig::new(
            Path::new("app")
                .join("src")
                .join("main")
                .join("java")
                .join("at")
                .join("jku")
                .join("ins")
                .join("chat")
                .join("ffi"),
            "at.jku.ins.chat.ffi".into(),
        )
            .use_null_annotation_from_package("androidx.annotation".into()),
    ))
        .rustfmt_bindings(true);
    //ANCHOR_END: config
    swig_gen.expand("android bindings", &in_src, &out_src);
    println!("cargo:rerun-if-changed={}", in_src.display());
}


#[cfg(not(target_os = "android"))]
fn main() {}