use arti_client::config::TorClientConfigBuilder;
use arti_client::TorClient;
use base64::Engine;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use futures::{Stream, StreamExt};
use futures_util::task::SpawnExt;
use http_body_util::BodyExt;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{header, Request, Response, StatusCode, Uri};
use hyper_util::rt::TokioIo;
use log::{error, info};
use rand::RngCore;
use sha3::{Digest, Sha3_256};
use std::panic;
use std::pin::Pin;
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use tor_cell::relaycell::msg::Connected;
use tor_hsservice::config::OnionServiceConfigBuilder;
use tor_llcrypto::pk::ed25519::ExpandedKeypair;
use tor_proto::stream::IncomingStreamRequest;
use tor_rtcompat::{PreferredRuntime, ToplevelBlockOn};
#[cfg(target_os = "android")]
use tracing_subscriber::{fmt::Subscriber, layer::SubscriberExt, util::SubscriberInitExt};

uniffi::setup_scaffolding!();

#[uniffi::export(callback_interface)]
pub trait OnEvent: Send {
    fn new_message(&self, s: ChatMessage);
}

#[derive(uniffi::Record, Clone)]
pub struct ChatMessage {
    pub signature: String,
    pub data_type: String,
    pub data: String,
}

#[derive(uniffi::Object)]
pub struct MessagingClient {
    client: TorClient<PreferredRuntime>,
    key_pair: Arc<Mutex<Option<[u8; 64]>>>,
    observers: Arc<Mutex<Vec<Box<dyn OnEvent>>>>,
}

#[uniffi::export]
impl MessagingClient {
    #[uniffi::constructor]
    pub fn new(cache_dir: &str) -> MessagingClient {
        #[cfg(target_os = "android")]
        panic::catch_unwind(|| {
            Subscriber::new()
                .with(tracing_android::layer("rust.arti").expect("error creating android logger"))
                .init(); // this must be called only once, otherwise your app will probably crash
        });

        eprintln!("Starting Tor client");

        let rt = if let Ok(runtime) = PreferredRuntime::current() {
            runtime
        } else {
            PreferredRuntime::create().expect("could not create async runtime")
        };

        let mut config = TorClientConfigBuilder::from_directories(
            format!("{cache_dir}{}arti-data", std::path::MAIN_SEPARATOR),
            format!("{cache_dir}{}arti-cache", std::path::MAIN_SEPARATOR),
        );
        config.address_filter().allow_onion_addrs(true);
        let config = config.build().expect("error building tor config");

        let binding = TorClient::with_runtime(rt.clone()).config(config);
        let client_future = binding.create_bootstrapped();

        rt.block_on(async {
            let client = client_future.await.unwrap();

            eprintln!("Tor client started");
            info!("Tor client started");

            MessagingClient {
                client,
                key_pair: Arc::new(Mutex::new(None)),
                observers: Arc::new(Mutex::new(Vec::new())),
            }
        })
    }

    pub fn onion_service_from_sk(&self, secret_key: &[u8]) -> String {
        let sk = <[u8; 32]>::try_from(secret_key).expect("could not convert to [u8; 32]");
        let sk = sk as ed25519_dalek::SecretKey;
        let esk = ed25519_dalek::hazmat::ExpandedSecretKey::from(&sk);

        let esk = [esk.scalar.to_bytes(), esk.hash_prefix].concat();
        self.onion_service_from_esk(esk.as_slice())
    }

    pub fn onion_service_from_esk(&self, expanded_secret_key: &[u8]) -> String {
        let expanded_secret =
            <[u8; 64]>::try_from(expanded_secret_key).expect("could not convert to [u8; 64]");
        let esk = ExpandedKeypair::from_secret_key_bytes(expanded_secret)
            .expect("error converting to ExpandedKeypair");
        let pk = esk.public();

        let mut value = self.key_pair.lock().unwrap();
        *value = Some(expanded_secret);

        let onion_address = get_onion_address(&pk.to_bytes());
        let clone_onion_address = onion_address.clone();
        let nickname = format!(
            "tor-chat-{}",
            onion_address.clone().chars().take(16).collect::<String>()
        );

        let encodable_key = tor_hscrypto::pk::HsIdKeypair::from(esk);

        let svc_cfg = OnionServiceConfigBuilder::default()
            .nickname(nickname.clone().parse().unwrap())
            .build()
            .unwrap();

        let (onion_service, request_stream): (
            _,
            Pin<Box<dyn Stream<Item = tor_hsservice::RendRequest> + Send>>,
        ) = match self
            .client
            .launch_onion_service_with_hsid(svc_cfg.clone(), encodable_key)
        {
            Ok((service, stream)) => (service, Box::pin(stream)),
            Err(_) => {
                // This key exists; reuse it
                let (service, stream) = self
                    .client
                    .launch_onion_service(svc_cfg)
                    .expect("error creating onion service");
                (service, Box::pin(stream))
            }
        };


        info!("onion service created: {}", onion_service.onion_address().unwrap());
        eprintln!("onion service created: {}", onion_service.onion_address().unwrap());

        info!("status: {:?}", onion_service.status());
        eprintln!("status: {:?}", onion_service.status());

        let observer_clone = self.observers.clone();

        self.client
            .clone()
            .runtime()
            .spawn(async move {
                info!("entering loop");
                eprintln!("entering loop");

                // #[cfg(target_os = "android")]
                // for cb in observer_clone.lock().unwrap().iter() {
                //     cb.new_message("entering loop");
                // }

                // service.status_events().take(1).for_each(|status| async move {
                //     info!("status: {:?}", status);
                //     eprintln!("status: {:?}", status);
                // }).await;

                let accepted_streams = tor_hsservice::handle_rend_requests(request_stream);

                tokio::pin!(accepted_streams);

                while let Some(stream_request) = accepted_streams.next().await {
                    let observer_clone = observer_clone.clone();

                    info!("new stream");
                    eprintln!("new stream");
                    let request = stream_request.request().clone();
                    match request {
                        IncomingStreamRequest::Begin(begin) if begin.port() == 80 => {
                            eprintln!("onion_service_stream");
                            let onion_service_stream =
                                stream_request.accept(Connected::new_empty()).await.unwrap();
                            let io = TokioIo::new(onion_service_stream);

                            http1::Builder::new()
                                .serve_connection(
                                    io,
                                    service_fn(|request| async {
                                        info!("request gotten");
                                        let path = request.uri().path();
                                        let binding = request.headers().clone();
                                        let signature = binding.get("X-Signature-Ed25519");
                                        if path == "/message" {
                                            let message =
                                                request.collect().await.unwrap().to_bytes();
                                            let message = String::from_utf8(message.to_vec())
                                                .expect("error parsing message");
                                            if let Some(signature) = signature {
                                                let signature = signature
                                                    .to_str()
                                                    .expect("error converting signature to string")
                                                    .to_string();
                                                let data_type = "message".to_string();
                                                let data = message;

                                                let message = ChatMessage {
                                                    signature,
                                                    data_type,
                                                    data,
                                                };

                                                for cb in observer_clone.lock().unwrap().iter() {
                                                    cb.new_message(message.clone());
                                                }
                                            }
                                        }
                                        Ok::<Response<String>, anyhow::Error>(
                                            Response::builder()
                                                .status(StatusCode::OK)
                                                .body("Message received".to_string())?,
                                        )
                                    }),
                                )
                                .await
                                .unwrap();
                        }
                        _ => {
                            stream_request.shutdown_circuit().unwrap();
                        }
                    };
                }
                drop(onion_service);
                info!("onion service dropped");
            })
            .expect("error spawning task");

        clone_onion_address
    }

    pub async fn send_message_inner(&self, message: &str, recipient: &str) -> String {
        let url: Uri = Uri::from_str(recipient).expect("error parsing recipient URL");
        let host = url.host().unwrap();

        info!("host parsed: {host}");
        eprintln!("host parsed: {host}");

        let Ok(stream) = self.client.connect((host, 80)).await else {
            eprintln!("could not connect to original recipient; message should be securely stored on caching server");
            info!("could not connect to original recipient; message should be securely stored on caching server");
            return "".to_string();
        };

        let (mut request_sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(stream))
                .await
                .unwrap();

        // spawn a task to poll the connection and drive the HTTP state
        tokio::spawn(async move {
            connection.await.unwrap();
        });

        let key = ExpandedKeypair::from_secret_key_bytes(
            self.key_pair.lock().unwrap().expect("could not borrow key"),
        )
        .unwrap();

        let resp = request_sender
            .send_request(
                Request::builder()
                    .uri("/message")
                    .header("Host", host)
                    .header(
                        "X-Signature-Ed25519",
                        base64::prelude::BASE64_STANDARD
                            .encode(key.sign(message.as_bytes()).to_bytes()),
                    )
                    .method("GET")
                    .body(message.to_string())
                    .unwrap(),
            )
            .await
            .unwrap();

        if let Some(content_type) = resp.headers().get(header::CONTENT_TYPE) {
            // Convert header value to a string
            if let Ok(content_type_str) = content_type.to_str() {
                eprintln!("Content-Type: {content_type_str}");
                info!("Content-Type: {content_type_str}");
            } else {
                eprintln!("Content-Type is not a valid string");
                info!("Content-Type is not a valid string");
            }
        } else {
            eprintln!("Content-Type header is missing");
            info!("Content-Type header is missing");
        }

        match resp.status().as_u16() {
            200 => {
                String::from_utf8(resp.into_body().collect().await.unwrap().to_bytes().into())
                    .expect("error unwrapping response into string")
                //"status 200 but no body".to_string()
            }
            _ => {
                format!("error: status {}", resp.status().as_u16())
            }
        }
    }

    pub fn send_message(&self, message: &str, recipient: &str) -> String {
        let runtime = self.client.runtime().clone();
        runtime.block_on(async { self.send_message_inner(message, recipient).await })
    }

    pub fn subscribe(&self, cb: Box<dyn OnEvent>) {
        let mut obs = self.observers.lock().unwrap();
        obs.push(cb);
    }
}

#[uniffi::export]
pub fn generate_key() -> Vec<u8> {
    let mut rng = rand::rng();
    let mut sk = [0u8; 32];
    rng.fill_bytes(&mut sk);
    sk.to_vec()
}
#[uniffi::export]
pub fn get_onion_address(public_key: &[u8]) -> String {
    let pub_key = <[u8; 32]>::try_from(public_key).expect("could not convert to [u8; 32]");
    let mut buf = [0u8; 35];
    pub_key.iter().copied().enumerate().for_each(|(i, b)| {
        buf[i] = b;
    });

    let mut h = Sha3_256::new();
    h.update(b".onion checksum");
    h.update(pub_key);
    h.update(b"\x03");

    let res_vec = h.finalize().to_vec();
    buf[32] = res_vec[0];
    buf[33] = res_vec[1];
    buf[34] = 3;

    base32::encode(base32::Alphabet::Rfc4648 { padding: false }, &buf).to_ascii_lowercase()
}
#[uniffi::export]
pub fn get_public_key_from_onion_address(onion_address: &str) -> Vec<u8> {
    panic::catch_unwind(|| {
        let mut res_vec: Vec<u8> = base32::decode(
            base32::Alphabet::Rfc4648Lower { padding: false },
            &*onion_address.to_ascii_lowercase(),
        )
        .unwrap_or_else(|| {
            eprintln!("error: {onion_address} could not convert from base32");
            Vec::<u8>::new()
        });
        res_vec.truncate(32);
        res_vec
    })
    .unwrap_or_else(|cause| {
        error!("{:?}", cause);
        Vec::<u8>::new()
    })
}
#[uniffi::export]
pub fn verify_signature(data: &str, signature: &[u8], public_key: &[u8]) -> bool {
    panic::catch_unwind(|| {
        let verifying_key =
            VerifyingKey::try_from(public_key).expect("could not convert public key");
        verifying_key
            .verify(
                data.as_bytes(),
                &Signature::try_from(signature).expect("signature bytes could not be converted"),
            )
            .is_ok()
    })
    .unwrap_or_else(|cause| {
        error!("{:?}", cause);
        false
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_onion_address() {
        let pk = vec![42u8; 32];
        let onion_address = get_onion_address(&pk);
        assert_eq!(
            onion_address,
            "fivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfivjcrid"
        );
    }

    #[test]
    fn test_start_server() {
        let mut client = MessagingClient::new(".");
        let pk = vec![42u8; 32];
        let onion_address = MessagingClient::onion_service_from_sk(&mut client, &pk);

        assert_eq!(
            onion_address,
            "df7wwi7bnsctfrvlza4pvtk6u6e34ddwwkjagnadtp5iwpjwrvq5bpad"
        );
    }
}
