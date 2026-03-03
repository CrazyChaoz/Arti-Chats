
relevant: https://gitlab.torproject.org/tpo/core/arti/-/blob/main/doc/Android.md

to run:
```{rust}
cargo build --release
cargo run --features=uniffi/cli --bin uniffi-bindgen generate --library target/release/libtor_chat.so --language kotlin --out-dir out
```