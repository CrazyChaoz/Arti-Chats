extern crate jni;

use jni::objects::{GlobalRef, JClass, JObject, JString};
use jni::sys::{jstring};
use jni::JNIEnv;
use std::sync::{Mutex};
use anyhow::Result;
use arti_client::config::TorClientConfigBuilder;
use arti_client::{TorClient, TorClientConfig};
use base64::Engine;
use rand::RngCore;
use sha3::{Digest, Sha3_256};
use std::io::Error;
use std::net::ToSocketAddrs;
use std::sync::{Arc};
use fs_mistrust::Mistrust;
use tor_keymgr::{ArtiEphemeralKeystore, KeyMgrBuilder, KeystoreSelector};
use tor_rtcompat::PreferredRuntime;
use tor_hsservice::config::OnionServiceConfigBuilder;
use tor_hsservice::{HsIdKeypairSpecifier, OnionService};
use tor_llcrypto::pk::ed25519::ExpandedKeypair;
use http_body_util::{BodyExt};
use hyper::body::{Incoming};
use hyper::{Request, Response, StatusCode, Uri};
use hyper_util::rt::TokioIo;
use lazy_static::lazy_static;
use tor_keymgr::key_specifier_derive::RawKeySpecifierComponentParser;
use tor_proto::stream::DataStream;

pub struct MessagingClient {
    pub client: TorClient<PreferredRuntime>,
    keystore: Arc<tor_keymgr::KeyMgr>,
}

impl MessagingClient {
    pub async fn default() -> Result<MessagingClient, Error> {
        let config = TorClientConfig::default();

        Self::new(config).await
    }


    pub async fn from_custom_cache(cache: &str) -> Result<MessagingClient, Error> {
        let config = TorClientConfigBuilder::from_directories(
            format!("{cache}{}arti-data", std::path::MAIN_SEPARATOR),
            format!("{cache}{}arti-cache", std::path::MAIN_SEPARATOR),
        )
            .build()
            .expect("error building Tor client config");

        Self::new(config).await
    }


    pub async fn new(config: TorClientConfig) -> Result<MessagingClient, Error> {
        let client = TorClient::create_bootstrapped(config)
            .await
            .expect("error bootstrapping tor client");
        let keystore_mgr = Arc::new(
            KeyMgrBuilder::default()
                .default_store(Box::new(ArtiEphemeralKeystore::new(
                    "in-memory-data-store".to_string(),
                )))
                .build()
                .expect("error building key manager"),
        );

        Ok(MessagingClient {
            client,
            keystore: keystore_mgr,
        })
    }
    pub async fn add_onion_v3_from_esk<
        S: ToSocketAddrs,
        I: IntoIterator<Item=(u16, S)> + Send + 'static,
    >(
        &mut self,
        expanded_secret_key: &[u8; 64]
    ) -> Result<String, Error>
    where
        <I as IntoIterator>::IntoIter: Send,
    {
        let esk = ExpandedKeypair::from_secret_key_bytes(*expanded_secret_key)
            .expect("error converting to ExpandedKeypair");
        let pk = esk.public();

        let onion_address = get_onion_address(&pk.to_bytes());
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
                    "chat-data",
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

        eprintln!("onion service created: {}", service.onion_name().unwrap());


        Ok(clone_onion_address)
    }


    pub async fn send_message(&self, message:  &str, recipient:  &'static str) -> Result<String> {
        let url: Uri = Uri::from_static(recipient);
        let host = url.host().unwrap();

        let stream:DataStream = self.client
            .connect((host, 80))
            .await
            .expect("connect failed");

        let (mut request_sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(stream)).await?;

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
                    .body(message.to_string())?,
            )
            .await?;

        match resp.status().as_u16() {
            200 => {
                if let Some(frame) = resp.body_mut().frame().await {
                    let bytes = frame?.into_data().unwrap();
                    return Ok(std::str::from_utf8(&bytes)?.to_string());
                }
                Err(anyhow::anyhow!("status 200 but no body"))
            }
            _ => {
                Err(anyhow::anyhow!("error: status {}",resp.status()))
            }
        }
    }
}

pub fn generate_key() -> [u8; 32] {
    let mut rng = rand::thread_rng();
    let mut sk = [0u8; 32];
    rng.fill_bytes(&mut sk);
    sk
}

pub fn get_onion_address(public_key: &[u8; 32]) -> String {
    let mut buf = [0u8; 35];
    public_key.iter().copied().enumerate().for_each(|(i, b)| {
        buf[i] = b;
    });

    let mut h = Sha3_256::new();
    h.update(b".onion checksum");
    h.update(public_key);
    h.update(b"\x03");

    let res_vec = h.finalize().to_vec();
    buf[32] = res_vec[0];
    buf[33] = res_vec[1];
    buf[34] = 3;

    base32::encode(base32::Alphabet::Rfc4648 { padding: false }, &buf).to_ascii_lowercase()
}


struct WebHandler {}

impl WebHandler {
    async fn serve(&self, request: Request<Incoming>) -> Result<Response<String>> {
        let path = request.uri().path();
        if path == "/message" {
            let message = request.body();
        }
        Ok(Response::builder().status(StatusCode::OK).body("Message received".to_string())?)
    }
}

// Global reference to the Java callback
struct Callback {
    java_callback: GlobalRef,
}

lazy_static! {
    static ref CALLBACK: Arc<Mutex<Option<Callback>>> = Arc::new(Mutex::new(None));
}

// Register the Java callback
#[no_mangle]
pub extern "system" fn Java_Messenger_registerCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    let callback_ref = env.new_global_ref(callback).expect("Couldn't create global ref");

    let mut cb = CALLBACK.lock().unwrap();
    *cb = Some(Callback {
        java_callback: callback_ref,
    });

    println!("Callback registered");
}

fn string_to_jstring(env: *mut JNIEnv, rust_str: String) -> JString {
    // let x = ::std::ffi::CString::new(rust_str).unwrap();
    unsafe { env.as_ref().expect("error converting to reference").new_string(rust_str).expect("issue converting string_to_jstring") }
}
fn jstring_to_string(env: *mut JNIEnv, js: jstring) -> String {
    if !js.is_null() {
        unsafe { env.as_ref().expect("error converting to reference").get_string_unchecked(&JString::from_raw(js)).expect("issue converting jstring_to_string").to_str().expect("").to_string()}
    } else {
        "".to_string()
    }
}

pub fn calculation_done(env: &mut JNIEnv) {
    let cb = CALLBACK.lock().unwrap();

    if let Some(ref callback) = *cb {

        let result = (&string_to_jstring(env,"result".to_string()));

        // Call Java method onCalculationReady with result
        env.call_method(
            callback.java_callback.as_obj(),
            "onCalculationReady",
            "(I)V",
            &[result.into()],
        )
            .expect("Failed to call Java method");
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn it_works() {}
}
