package at.jku.ins.chat

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateListOf

import at.jku.ins.chat.ffi.MessagingClient
import at.jku.ins.chat.ui.theme.TorChatTheme


data class Message(val text: String, val isMe: Boolean)


class MainActivity : ComponentActivity() {
    private lateinit var chatService: MessagingClient
    private val messages = mutableStateListOf<Message>()
    private var address by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        System.loadLibrary("magic_chat_rust_lib")

        chatService = MessagingClient(cacheDir.absolutePath)
        chatService.subscribe { message ->
            println("Received message: $message")
        }

        setContent {
            TorChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    ChatApp(
                        messages = messages,
                        address = address,
                        onAddressChange = { newAddress -> address = newAddress },
                        onSend = { newMessage ->
                            if (isValidOnionUrl(address)) {
                                messages.add(Message(newMessage, isMe = true))
                                chatService.send_message(newMessage, address)
                            } else {
                                println("Invalid address")
                            }
                        }
                    )
                }
            }
        }
    }
}

fun isValidOnionUrl(address: String): Boolean {
    val onionRegex = Regex("^[a-zA-Z0-9]{16}\\.onion$")
    return onionRegex.matches(address)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(
    messages: List<Message>,
    address: String,
    onAddressChange: (String) -> Unit,
    onSend: (String) -> Unit
) {
    Scaffold(topBar = {
        Column {
            TopAppBar(title = {
                Text(
                    text = "My Chat Title",
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { /* Add copy to clipboard action */ }
                )
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

        IconButton(onClick = { /* Add QR Code scan action */ }) {
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
            }
        )
    }
}