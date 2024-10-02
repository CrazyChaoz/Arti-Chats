package at.jku.ins.chat


import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color.rgb
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat.getSystemService
import at.jku.ins.chat.ffi.MessagingClient
import at.jku.ins.chat.ui.theme.TorChatTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Message(val text: String, val isMe: Boolean, var received: Boolean = false)


class MainActivity() : ComponentActivity() {
    private var chatService: MessagingClient? = null
    private val messages = mutableStateListOf<Message>()
    private var partnerAddress by mutableStateOf("")
    private var chatServiceAddress by mutableStateOf("Own Address")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        System.loadLibrary("magic_chat_rust_lib")
        val dir=applicationContext.cacheDir.absolutePath
        CoroutineScope(Dispatchers.IO).launch {
            if (chatService == null) {
                chatService = MessagingClient(dir)
                chatService!!.subscribe { message ->
                    println("Received message: $message")
                    messages.add(Message(message, isMe = false))
                }
                chatServiceAddress =
                    chatService!!.onion_service_from_sk(MessagingClient.generate_key()) + ".onion"
            }
        }

        setContent {
            TorChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    ChatApp(
                        messages = messages,
                        address = partnerAddress,
                        onAddressChange = { newAddress -> partnerAddress = newAddress },
                        onSend = { newMessage ->
                            if (isValidOnionUrl(partnerAddress)) {
                                val message = Message(newMessage, isMe = true)
                                messages.add(message)
                                CoroutineScope(Dispatchers.IO).launch {
                                    chatService!!.send_message(newMessage, "http://$partnerAddress")
                                    message.received = true
                                }
                            } else {
                                println("Invalid address")
                            }
                        },
                        onRegenerate = {
                            println("TODO: Regenerate address")
                        },
                        chatServiceAddress = chatServiceAddress
                    )
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleQRCodeResult(requestCode, resultCode, data) { newAddress ->
            println("Scanned address: $newAddress")
            partnerAddress = newAddress
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(
    messages: List<Message>,
    address: String,
    onAddressChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onRegenerate: () -> Unit,
    chatServiceAddress: String
) {
    var expanded by remember { mutableStateOf(false) }
    var showQRCode by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(topBar = {
        Column {
            TopAppBar(title = {
                Box {
                    Text(
                        text = chatServiceAddress,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = true }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Regenerate") },
                            onClick = {
                                expanded = false
                                // Regenerate address logic here
                                onRegenerate()
                            })
                        DropdownMenuItem(
                            text = { Text("Copy to Clipboard") },
                            onClick = {
                                expanded = false
                                val clipboard =
                                    getSystemService(context, ClipboardManager::class.java)
                                val clip = ClipData.newPlainText(
                                    "Chat Service Address",
                                    chatServiceAddress
                                )
                                clipboard?.setPrimaryClip(clip)
                            })
                        DropdownMenuItem(
                            text = { Text("Generate QR Code") },
                            onClick = {
                                expanded = false
                                showQRCode = true
                            })
                    }
                }
            }, navigationIcon = {
                IconButton(onClick = { /* Open burger menu */ }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_burger_menu),
                        contentDescription = "Menu"
                    )
                }
            })
            AddressRow(address = address, onAddressChange = onAddressChange)
        }
    }, content = { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ChatMessages(messages = messages)
        }
    }, bottomBar = {
        ChatInput(onSend = onSend)
    })

    if (showQRCode) {
        AlertDialog(
            onDismissRequest = { showQRCode = false },
            confirmButton = {
                TextButton(onClick = { showQRCode = false }) {
                    Text("Close")
                }
            },
            title = {
                Text("Your Chat Service Address")
            },
            text = {
                GenerateQRCode(chatServiceAddress)
            }
        )
    }
}

fun isValidOnionUrl(address: String): Boolean {
    val onionRegex = Regex("[a-z2-7]{56}.onion")
    return onionRegex.matches(address)
}


@Composable
fun GenerateQRCode(text: String) {
    val size = 256 // Size of the QR code
//    val qrCodeWriter = BarcodeEnc/.encodeBitmap(text, BarcodeFormat.QR_CODE, size, size)
    val qrCodeWriter = QRCodeWriter()
    val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) rgb(0, 0, 0) else rgb(255, 255, 255))
        }
    }

    Box(
//        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR Code")
    }
}

@Composable
fun AddressRow(address: String, onAddressChange: (String) -> Unit) {
    val activity = LocalContext.current as Activity

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(Color.LightGray),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = address,
            onValueChange = onAddressChange,
            modifier = Modifier
                .weight(1f)
                .padding(8.dp)
                .background(Color.White)
        )

        IconButton(onClick = { startQRCodeScanner(activity) }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_qr_code),
                contentDescription = "QR Code Scan"
            )
        }

        IconButton(onClick = { /* Add address to address book action */ }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = "Add to Address Book"
            )
        }
    }
}


fun startQRCodeScanner(activity: Activity) {
    val integrator = IntentIntegrator(activity)
    integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
    integrator.setPrompt("Scan a QR code")
    integrator.setCameraId(0) // Use a specific camera of the device
    integrator.setBeepEnabled(true)
    integrator.setBarcodeImageEnabled(true)
    integrator.initiateScan()
}

fun handleQRCodeResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
    onAddressChange: (String) -> Unit
) {
    val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
    if (result.contents != null) {
        val scannedAddress = result.contents
        println("Scanned address: $scannedAddress")
        if (isValidOnionUrl(scannedAddress)) {
            onAddressChange(scannedAddress)
        } else {
            println("Invalid QR code content")
        }
    }
}

@Composable
fun ChatMessages(messages: List<Message>) {
    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        items(messages) { message ->
            ChatBubble(message)
        }
    }
}

@Composable
fun ChatBubble(message: Message) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        val alignment = if (message.isMe) Alignment.CenterEnd else Alignment.CenterStart
        val backgroundColor = if (message.isMe) Color.Blue else Color.Gray
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (message.isMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (message.isMe) {
                Text(
                    text = if (message.received) "✓" else "⏳",
                    color = Color.White,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Text(
                text = message.text,
                modifier = Modifier
                    .background(backgroundColor)
                    .padding(8.dp),
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 10
            )
        }
    }
}

@Composable
fun ChatInput(onSend: (String) -> Unit) {
    var message by remember { mutableStateOf(TextFieldValue("")) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier
                .weight(1f)
                .padding(8.dp)
                .background(Color.White)
        )

        // Send button
        IconButton(onClick = {
            if (message.text.isNotBlank()) {
                onSend(message.text)
                message = TextFieldValue("") // Clear the input field
            }
        }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_send), contentDescription = "Send"
            )
        }
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    val messages = mutableListOf<Message>()
    var address by mutableStateOf("")
    TorChatTheme {
        ChatApp(
            messages = messages,
            address = address,
            onAddressChange = { newAddress -> address = newAddress },
            onSend = { newMessage ->
                messages.add(Message(newMessage, isMe = true))
            },
            chatServiceAddress = "Title",
            onRegenerate = {
                println("TODO: Regenerate address")
            }
        )
    }
}