use arti_client::config::TorClientConfigBuilder;
use arti_client::{TorClient};
use fs_mistrust::Mistrust;
use futures::StreamExt;
use http_body_util::BodyExt;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{body, header, Request, Response, StatusCode, Uri};
use hyper_util::rt::TokioIo;
use log::info;
use rand::RngCore;
use sha3::{Digest, Sha3_256};
use std::pin::pin;
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use futures_util::task::SpawnExt;
use tor_cell::relaycell::msg::Connected;
use tor_hsservice::config::OnionServiceConfigBuilder;
use tor_hsservice::{HsIdKeypairSpecifier, OnionService};
use tor_keymgr::{ArtiEphemeralKeystore, KeyMgrBuilder, KeystoreSelector};
use tor_llcrypto::pk::ed25519::ExpandedKeypair;
use tor_proto::stream::{DataStream, IncomingStreamRequest};
use tor_rtcompat::{BlockOn, PreferredRuntime};

#[cfg(target_os = "android")]
use tracing_subscriber::{
    fmt::Subscriber,
    layer::SubscriberExt,
    util::SubscriberInitExt,
};

#[cfg(target_os = "android")]
mod java_glue;
#[cfg(target_os = "android")]
pub use crate::java_glue::*;

pub struct MessagingClient {
    client: TorClient<PreferredRuntime>,
    keystore: Arc<tor_keymgr::KeyMgr>,
    cache_dir: String,
    #[cfg(target_os = "android")]
    observers: Arc<Mutex<Vec<Box<dyn java_glue::OnEvent>>>>,
}

impl MessagingClient {
    pub fn new(cache_dir: &str) -> MessagingClient {
        #[cfg(target_os = "android")]
        Subscriber::new()
            .with(tracing_android::layer("rust.arti").expect("error creating android logger"))
            .init(); // this must be called only once, otherwise your app will probably crash

        eprintln!("Starting Tor client");

        let rt = if let Ok(runtime) = PreferredRuntime::current() { runtime } else { PreferredRuntime::create().expect("could not create async runtime") };

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

            let keystore_mgr = Arc::new(
                KeyMgrBuilder::default()
                    .default_store(Box::new(ArtiEphemeralKeystore::new(
                        "in-memory-data-store".to_string(),
                    )))
                    .build()
                    .expect("error building key manager"),
            );

            MessagingClient {
                client,
                keystore: keystore_mgr,
                cache_dir: cache_dir.to_string(),
                #[cfg(target_os = "android")]
                observers: Arc::new(Mutex::new(Vec::new())),
            }
        })
    }

    pub fn all_in_one(secret_key: &[i16], cache_dir: &str) {
        //#[cfg(target_os = "android")]
        // Subscriber::new()
        //     .with(tracing_android::layer("rust.arti").expect("error creating android logger"))
        //     .init(); // this must be called only once, otherwise your app will probably crash

        let rt = if let Ok(runtime) = PreferredRuntime::current() { runtime } else { PreferredRuntime::create().expect("could not create async runtime") };

        rt.block_on(async {
            let mut config = TorClientConfigBuilder::from_directories(
                format!("{cache_dir}{}arti-data", std::path::MAIN_SEPARATOR),
                format!("{cache_dir}{}arti-cache", std::path::MAIN_SEPARATOR),
            );
            config.address_filter().allow_onion_addrs(true);
            let config = config.build().expect("error building tor config");


            eprintln!("Starting Tor client");

            let client = TorClient::create_bootstrapped(config).await.unwrap();
            let keystore_mgr = Arc::new(
                KeyMgrBuilder::default()
                    .default_store(Box::new(ArtiEphemeralKeystore::new(
                        "in-memory-data-store".to_string(),
                    )))
                    .build()
                    .expect("error building key manager"),
            );

            eprintln!("Tor client started");

            let msg_client = MessagingClient {
                client,
                keystore: keystore_mgr,
                cache_dir: cache_dir.to_string(),
                #[cfg(target_os = "android")]
                observers: Arc::new(Mutex::new(Vec::new())),
            };

            let positive_secret_key = secret_key.iter().map(|x| x.abs() as u8).collect::<Vec<u8>>();
            let sk = <[u8; 32]>::try_from(positive_secret_key).expect("could not convert to [u8; 32]");
            let sk = sk as ed25519_dalek::SecretKey;
            let esk = ed25519_dalek::hazmat::ExpandedSecretKey::from(&sk);
            let esk = <[u8; 64]>::try_from([esk.scalar.to_bytes(), esk.hash_prefix].concat()).expect("could not convert to [u8; 64]");

            let esk = ExpandedKeypair::from_secret_key_bytes(esk)
                .expect("error converting to ExpandedKeypair");
            let pk = esk.public();

            let onion_address = MessagingClient::get_onion_address(&pk.to_bytes().map(|x| x as i16));
            let clone_onion_address = onion_address.clone();
            let nickname = format!(
                "tor-chat-{}",
                onion_address.clone().chars().take(16).collect::<String>()
            );

            let encodable_key = tor_hscrypto::pk::HsIdKeypair::from(esk);

            msg_client.keystore
                .clone()
                .insert(
                    encodable_key,
                    &HsIdKeypairSpecifier::new(nickname.clone().parse().unwrap()),
                    KeystoreSelector::Default,
                )
                .expect("error inserting keypair into keystore");

            let clone_keystore = msg_client.keystore.clone();
            let clone_client = msg_client.client.clone();

            let svc_cfg = OnionServiceConfigBuilder::default()
                .nickname(nickname.clone().parse().unwrap())
                .build()
                .unwrap();

            let onion_service = OnionService::builder()
                .config(svc_cfg)
                .keymgr(clone_keystore.clone())
                .state_dir(
                    tor_persist::state_dir::StateDirectory::new(
                        format!("{}{}chat-data", msg_client.cache_dir, std::path::MAIN_SEPARATOR),
                        &Mistrust::new_dangerously_trust_everyone(),
                    )
                        .expect("error creating state directory"),
                )
                .build()
                .expect("error building onion service");

            let (service, request_stream) = onion_service
                .launch(
                    clone_client.runtime().clone(),
                    clone_client.dirmgr().clone().upcast_arc(),
                    clone_client.hs_circ_pool().clone(),
                )
                .unwrap();


            #[cfg(target_os = "android")]
            info!( "onion service created: {}", service.onion_name().unwrap());

            info!("onion service created: {}", service.onion_name().unwrap());
            eprintln!("onion service created: {}", service.onion_name().unwrap());

            info!("status: {:?}", service.status());
            eprintln!("status: {:?}", service.status());

            #[cfg(target_os = "android")]
            let observer_clone = msg_client.observers.clone();


            info!("entering loop");
            eprintln!("entering loop");

            #[cfg(target_os = "android")]
            for cb in observer_clone.lock().unwrap().iter() {
                cb.new_message("entering loop");
            }

            // service.status_events().take(1).for_each(|status| async move {
            //     info!("status: {:?}", status);
            //     eprintln!("status: {:?}", status);
            // }).await;

            let mut accepted_streams = tor_hsservice::handle_rend_requests(request_stream);

            tokio::pin!(accepted_streams);

            while let Some(stream_request) = accepted_streams.next().await {
                #[cfg(target_os = "android")]
                let observer_clone = observer_clone.clone();

                info!("new stream");
                eprintln!("new stream");
                let request = stream_request.request().clone();
                let _ = match request {
                    IncomingStreamRequest::Begin(begin) if begin.port() == 80 => {
                        eprintln!("onion_service_stream");
                        let onion_service_stream = stream_request.accept(Connected::new_empty()).await.unwrap();
                        let io = TokioIo::new(onion_service_stream);

                        let _ = http1::Builder::new().serve_connection(io, service_fn(|request| async {
                            info!("request gotten");
                            let path = request.uri().path();
                            if path == "/message" {
                                let message = request.collect().await.unwrap().to_bytes();
                                let message = String::from_utf8(message.to_vec()).expect("error parsing message");

                                #[cfg(target_os = "android")]
                                for cb in observer_clone.lock().unwrap().iter() {
                                    cb.new_message(&message);
                                }
                            }
                            Ok::<Response<String>, anyhow::Error>(Response::builder().status(StatusCode::OK).body("Message received".to_string())?)
                        })).await.unwrap();
                    }
                    _ => {
                        stream_request.shutdown_circuit().unwrap();
                    }
                };
            }
            drop(service);

            info!("onion service dropped");
        })
    }

    pub fn add_onion_v3_from_sk(
        &mut self,
        secret_key: &[i16],
    ) -> String {
        let positive_secret_key = secret_key.iter().map(|x| x.abs() as u8).collect::<Vec<u8>>();
        let sk = <[u8; 32]>::try_from(positive_secret_key).expect("could not convert to [u8; 32]");
        let sk = sk as ed25519_dalek::SecretKey;
        let esk = ed25519_dalek::hazmat::ExpandedSecretKey::from(&sk);
        let esk = [esk.scalar.to_bytes(), esk.hash_prefix].concat();
        let esk: Vec<i16> = esk.into_iter().map(|x| x as i16).collect();
        self.add_onion_v3_from_esk(esk.as_slice())
    }

    pub fn add_onion_v3_from_esk(
        &mut self,
        expanded_secret_key: &[i16],
    ) -> String {
        let positive_secret_key = expanded_secret_key.iter().map(|x| x.abs() as u8).collect::<Vec<u8>>();
        let expanded_secret = <[u8; 64]>::try_from(positive_secret_key).expect("could not convert to [u8; 64]");
        let esk = ExpandedKeypair::from_secret_key_bytes(expanded_secret)
            .expect("error converting to ExpandedKeypair");
        let pk = esk.public();

        let onion_address = MessagingClient::get_onion_address(&pk.to_bytes().map(|x| x as i16));
        let clone_onion_address = onion_address.clone();
        let nickname = format!(
            "tor-chat-{}",
            onion_address.clone().chars().take(16).collect::<String>()
        );

        let encodable_key = tor_hscrypto::pk::HsIdKeypair::from(esk);

        self.keystore
            .clone()
            .insert(
                encodable_key,
                &HsIdKeypairSpecifier::new(nickname.clone().parse().unwrap()),
                KeystoreSelector::Default,
            )
            .expect("error inserting keypair into keystore");

        let clone_keystore = self.keystore.clone();
        let clone_client = self.client.clone();

        let svc_cfg = OnionServiceConfigBuilder::default()
            .nickname(nickname.clone().parse().unwrap())
            .build()
            .unwrap();

        let onion_service = OnionService::builder()
            .config(svc_cfg)
            .keymgr(clone_keystore.clone())
            .state_dir(
                tor_persist::state_dir::StateDirectory::new(
                    format!("{}{}chat-data", self.cache_dir, std::path::MAIN_SEPARATOR),
                    &Mistrust::new_dangerously_trust_everyone(),
                )
                    .expect("error creating state directory"),
            )
            .build()
            .expect("error building onion service");

        let (service, request_stream) = onion_service
            .launch(
                clone_client.runtime().clone(),
                clone_client.dirmgr().clone().upcast_arc(),
                clone_client.hs_circ_pool().clone(),
            )
            .unwrap();

        info!("onion service created: {}", service.onion_name().unwrap());
        eprintln!("onion service created: {}", service.onion_name().unwrap());

        info!("status: {:?}", service.status());
        eprintln!("status: {:?}", service.status());

        #[cfg(target_os = "android")]
        let observer_clone = self.observers.clone();


        self.client.clone().runtime().spawn(async move {
            info!("entering loop");
            eprintln!("entering loop");

            #[cfg(target_os = "android")]
            for cb in observer_clone.lock().unwrap().iter() {
                cb.new_message("entering loop");
            }

            // service.status_events().take(1).for_each(|status| async move {
            //     info!("status: {:?}", status);
            //     eprintln!("status: {:?}", status);
            // }).await;

            let mut accepted_streams = tor_hsservice::handle_rend_requests(request_stream);

            tokio::pin!(accepted_streams);

            while let Some(stream_request) = accepted_streams.next().await {
                #[cfg(target_os = "android")]
                let observer_clone = observer_clone.clone();

                info!("new stream");
                eprintln!("new stream");
                let request = stream_request.request().clone();
                let _ = match request {
                    IncomingStreamRequest::Begin(begin) if begin.port() == 80 => {
                        eprintln!("onion_service_stream");
                        let onion_service_stream = stream_request.accept(Connected::new_empty()).await.unwrap();
                        let io = TokioIo::new(onion_service_stream);

                        let _ = http1::Builder::new().serve_connection(io, service_fn(|request| async {
                            info!("request gotten");
                            let path = request.uri().path();
                            if path == "/message" {
                                let message = request.collect().await.unwrap().to_bytes();
                                let message = String::from_utf8(message.to_vec()).expect("error parsing message");

                                #[cfg(target_os = "android")]
                                for cb in observer_clone.lock().unwrap().iter() {
                                    cb.new_message(&message);
                                }
                            }
                            Ok::<Response<String>, anyhow::Error>(Response::builder().status(StatusCode::OK).body("Message received".to_string())?)
                        })).await.unwrap();
                    }
                    _ => {
                        stream_request.shutdown_circuit().unwrap();
                    }
                };
            }
            drop(service);
            info!("onion service dropped");
        }).expect("error spawning task");

        clone_onion_address
    }

    pub async fn send_message_inner(&self, message: &str, recipient: &str) -> String {
        let url: Uri = Uri::from_str(recipient).expect("error parsing recipient URL");
        let host = url.host().unwrap();


        let stream: DataStream = self.client
            .connect((host, 80))
            .await
            .expect("connect failed");



        let (mut request_sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(stream)).await.unwrap();

        // spawn a task to poll the connection and drive the HTTP state
        tokio::spawn(async move {
            connection.await.unwrap();
        });

        let mut resp = request_sender
            .send_request(
                Request::builder()
                    .uri("/")
                    .header("Host", host)
                    .method("GET")
                    .body(message.to_string()).unwrap(),
            )
            .await.unwrap();

        if let Some(content_type) = resp.headers().get(header::CONTENT_TYPE) {
            // Convert header value to a string
            if let Ok(content_type_str) = content_type.to_str() {
                eprintln!("Content-Type: {}", content_type_str);
                info!("Content-Type: {}", content_type_str);
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
                String::from_utf8(resp.into_body().collect().await.unwrap().to_bytes().into()).expect("error unwrapping response into string")
                //"status 200 but no body".to_string()
            }
            _ => {
                "error: status {}".to_string()
            }
        }
    }

    pub fn send_message(&self, message: String, recipient: String) -> String {
        self.client.runtime().block_on(async {
            self.send_message_inner(&*message, &*recipient).await
        })
    }
    pub fn generate_key() -> Vec<i16> {
        let mut rng = rand::thread_rng();
        let mut sk = [0u8; 32];
        rng.fill_bytes(&mut sk);
        let sk: Vec<i16> = sk.map(|x| x as i16).to_vec();
        sk
    }


    pub fn get_onion_address(public_key: &[i16]) -> String {
        let positive_key = public_key.iter().map(|x| x.abs() as u8).collect::<Vec<u8>>();
        let pub_key = <[u8; 32]>::try_from(positive_key).expect("could not convert to [u8; 32]");
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

    #[cfg(target_os = "android")]
    fn subscribe(&mut self, cb: Box<dyn java_glue::OnEvent>) {
        let mut obs = self.observers.lock().unwrap();
        obs.push(cb);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_onion_address() {
        let pk = vec![42i16; 32];
        let onion_address = MessagingClient::get_onion_address(&pk);
        assert_eq!(onion_address, "fivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfivcukrkfivjcrid");
    }

    #[test]
    fn test_start_server() {
        let mut client = MessagingClient::new(".");
        let pk = vec![42i16; 32];
        let onion_address = MessagingClient::add_onion_v3_from_sk(&mut client, &pk);

        assert_eq!(onion_address, "df7wwi7bnsctfrvlza4pvtk6u6e34ddwwkjagnadtp5iwpjwrvq5bpad");
    }
}