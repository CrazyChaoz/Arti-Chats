package at.jku.ins.chat


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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat.getSystemService
import at.jku.ins.chat.ui.theme.TorChatTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tor_chat.ChatMessage
import uniffi.tor_chat.OnEvent
import uniffi.tor_chat.getPublicKeyFromOnionAddress
import uniffi.tor_chat.verifySignature
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.MutableState


data class Message(val text: String, val isMe: Boolean, var received: Boolean = false, var error: Boolean = false)

object ProgramState {
    val messages = mutableStateMapOf<String, MutableList<MutableState<Message>>>()
    var partnerAddress = mutableStateOf("")
    var chatServiceAddress by mutableStateOf("")
}

class MainActivity() : ComponentActivity() {

    @OptIn(ExperimentalEncodingApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProgramState.messages[""] = mutableStateListOf()

        startService(Intent(this, MessagingService::class.java))

        CoroutineScope(Dispatchers.IO).launch {
            while (MessagingService.chatService == null) {
                println("Waiting for background service to start")
                Thread.sleep(1000)
            }


            MessagingService.chatService!!.subscribe(object : OnEvent {
                override fun newMessage(message: ChatMessage) {
                    if (message.dataType == "message") {
                        for (address in ProgramState.messages.keys) {
                            val publicKey =
                                getPublicKeyFromOnionAddress(address.dropLast(6))
                            val signature = Base64.Default.decode(message.signature)
                            if (verifySignature(message.data, signature, publicKey)) {
                                println("new message from $address")
                                ProgramState.messages[address]?.add(
                                    mutableStateOf(
                                        Message(
                                            message.data,
                                            isMe = false,
                                            received = true
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            })

            ProgramState.chatServiceAddress = MessagingService.ownOnionAddress!!
        }

        setContent {
            TorChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    ChatApp(
                        onAddressChange = { newAddress ->
                            if (isValidOnionUrl(newAddress)) {
                                if (!ProgramState.messages.containsKey(newAddress)) {
                                    ProgramState.messages[newAddress] = mutableStateListOf()
                                }
                                ProgramState.partnerAddress.value = newAddress
                            }
                        },
                        onSend = { newMessage ->
                            if (isValidOnionUrl(ProgramState.partnerAddress.value)) {
                                val message = mutableStateOf(Message(newMessage, isMe = true))
                                ProgramState.messages[ProgramState.partnerAddress.value]?.add(
                                    message
                                )
                                CoroutineScope(Dispatchers.IO).launch {
                                    val ok = MessagingService.chatService?.sendMessage(
                                        newMessage,
                                        "http://${ProgramState.partnerAddress.value}"
                                    )

                                    if (ok.equals("Message received")) {
                                        withContext(Dispatchers.Main) {
                                            message.value = message.value.copy(received = true)
                                        }
                                    }else{
                                        withContext(Dispatchers.Main) {
                                            message.value = message.value.copy(error = true)
                                        }
                                    }
                                }
                            } else {
                                println("Invalid address")
                            }
                        },
                        onRegenerate = {
                            println("TODO: Regenerate address")
                        },
                        chatServiceAddress = ProgramState.chatServiceAddress
                    )
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleQRCodeResult(requestCode, resultCode, data) { newAddress ->
            if (!ProgramState.messages.containsKey(newAddress)) {
                ProgramState.messages[newAddress] = mutableStateListOf()
            }
            ProgramState.partnerAddress.value = newAddress
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(
    onAddressChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onRegenerate: () -> Unit,
    chatServiceAddress: String
) {
    var topBarExpanded by remember { mutableStateOf(false) }
    var addContactExpanded by remember { mutableStateOf(false) }
    var showQRCode by remember { mutableStateOf(false) }
    var showOwnAddress by remember { mutableStateOf(false) }
    var addressListExpanded by remember { mutableStateOf(false) }
    var showAddManuallyPopup by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(topBar = {
        Column {
            TopAppBar(title = {
                Text(
                    text = ProgramState.partnerAddress.value.ifEmpty { "Main Menu" },
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { /*topBarExpanded = true */ }
                )
            }, navigationIcon = {
                IconButton(onClick = { topBarExpanded = true }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_burger_menu),
                        contentDescription = "Menu"
                    )
                    DropdownMenu(
                        expanded = topBarExpanded,
                        onDismissRequest = { topBarExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add Contact") },
                            onClick = {
                                topBarExpanded = false
                                addContactExpanded = true
                            })
                        DropdownMenuItem(
                            text = { Text("List Contacts") },
                            onClick = {
                                topBarExpanded = false
                                addressListExpanded = true
                            })
                        DropdownMenuItem(
                            text = { Text("Show Own Address") },
                            onClick = {
                                topBarExpanded = false
                                showOwnAddress = true
                            })
                    }
                    DropdownMenu(
                        expanded = addressListExpanded,
                        onDismissRequest = { addressListExpanded = false }
                    ) {
                        ProgramState.messages.keys.filter { it.isNotEmpty() }.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key) },
                                onClick = {
                                    addressListExpanded = false
                                    onAddressChange(key)
                                }
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = addContactExpanded,
                        onDismissRequest = { addContactExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add via QR Code") },
                            onClick = {
                                addContactExpanded = false
                                startQRCodeScanner(context as Activity)
                            })
                        DropdownMenuItem(
                            text = { Text("Add Manually") },
                            onClick = {
                                addContactExpanded = false
                                showAddManuallyPopup = true
                                // Handle adding manually
                            })
                    }
                }
            })
        }
    }, content = { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ChatMessages(
                messages = ProgramState.messages,
                address = ProgramState.partnerAddress.value
            )
        }
    }, bottomBar = {
        ChatInput(onSend = onSend)
    })

    if (showAddManuallyPopup) {
        AddManuallyPopup(
            onDismiss = { showAddManuallyPopup = false },
            onAddContact = { newAddress, nickname ->
                if (!ProgramState.messages.containsKey(newAddress)) {
                    ProgramState.messages[newAddress] = mutableStateListOf()
                }
                // Handle nickname if needed
                showAddManuallyPopup = false
            }
        )
    }

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

    if (showOwnAddress) {
        AlertDialog(
            onDismissRequest = { showOwnAddress = false },
            confirmButton = {
                TextButton(onClick = { showOwnAddress = false }) {
                    Text("Close")
                }
            },
            title = {
                Text("Your Chat Service Address")
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = chatServiceAddress,
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { showQRCode = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_qr_code),
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Show QR Code")
                        }
                        Button(onClick = { onRegenerate() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Regenerate")
                        }
                        Button(onClick = {
                            val clipboard =
                                getSystemService(context, ClipboardManager::class.java)
                            val clip = ClipData.newPlainText(
                                "Chat Service Address",
                                chatServiceAddress
                            )
                            clipboard?.setPrimaryClip(clip)
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy to Clipboard")
                        }
                    }
                }
            }
        )
    }
}

fun isValidOnionUrl(address: String): Boolean {
    val onionRegex = Regex("[a-z2-7]{30,}\\.onion")
    return onionRegex.matches(address)
}


@Composable
fun AddManuallyPopup(onDismiss: () -> Unit, onAddContact: (String, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onAddContact(address, nickname)
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text("Add Contact Manually")
        },
        text = {
            Column {
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .background(Color.White),
                    decorationBox = { innerTextField ->
                        if (address.isEmpty()) {
                            Text("Address", color = Color.Gray)
                        }
                        innerTextField()
                    }
                )
                BasicTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .background(Color.White),
                    decorationBox = { innerTextField ->
                        if (nickname.isEmpty()) {
                            Text("Nickname", color = Color.Gray)
                        }
                        innerTextField()
                    }
                )
            }
        }
    )
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

        IconButton(onClick = { }) {
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
fun ChatMessages(
    messages: MutableMap<String, MutableList<MutableState<Message>>>,
    address: String
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages[address]?.size) {
        listState.animateScrollToItem(messages[address]?.size ?: 0)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.padding(8.dp)
    ) {
        items(messages[address]!!) { message ->
            ChatBubble(message)
        }
    }
}

@Composable
fun ChatBubble(message: MutableState<Message>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        val backgroundColor = if (message.value.isMe) Color.Blue else Color.Gray
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (message.value.isMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (message.value.isMe) {
                Icon(
                    imageVector = if (message.value.received) Icons.Default.Check else if (message.value.error) Icons.Default.Warning else Icons.Default.Email,
                    contentDescription = "Received",
                    tint = Color.Blue,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Text(
                text = message.value.text,
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
            .navigationBarsPadding()
            .imePadding()
            .fillMaxWidth()
            .padding(8.dp)
            .background(Color.LightGray),
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

//@SuppressLint("UnrememberedMutableState")
//@Preview(showBackground = true)
//@Composable
//fun GreetingPreview() {
//    val messages = mutableStateMapOf<String, MutableList<Message>>()
//    var address by mutableStateOf("")
//    TorChatTheme {
//        ChatApp(
//            messages = messages,
//            address = address,
//            onAddressChange = { newAddress -> address = newAddress },
//            onSend = { newMessage ->
//                messages[""] = mutableStateListOf(Message(newMessage, isMe = true))
//            },
//            chatServiceAddress = "Title",
//            onRegenerate = {
//                println("TODO: Regenerate address")
//            }
//        )
//    }
//}