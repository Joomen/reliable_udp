package com.socket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.socket.ui.theme.SocketTheme
import android.util.Log
import java.io.*
import java.net.*
import java.util.*

// 이미지 파일 경로 설정
val IMAGE_PATH = "/path/to/your/image.jpg"

// 서버 정보 설정 (실제 사용할 서버 주소와 포트 입력)
var serverAddress = "your.server.ip.address"
var serverPort = 12345

// 전송 속도를 측정하기 위한 시작 시간
var startTime: Long = 0

// 이미지 데이터를 바이트 배열로 읽기
fun loadImageData(): ByteArray {
    return File(IMAGE_PATH).readBytes()
}
fun sendImageTCP(serverAddress: String, serverPort: Int) {
    try {
        val socket = Socket(serverAddress, serverPort)
        val outputStream = socket.getOutputStream()
        val imageData = loadImageData()

        startTime = System.currentTimeMillis()
        outputStream.write(imageData)
        outputStream.flush()
        outputStream.close()
        socket.close()

        Log.d("TCP", "TCP Image sent successfully in ${System.currentTimeMillis() - startTime} ms")
    } catch (e: IOException) {
        Log.e("TCP", "TCP Error: ${e.message}")
    }
}
fun sendImageUDP(serverAddress: String, serverPort: Int) {
    try {
        val socket = DatagramSocket()
        val address = InetAddress.getByName(serverAddress)
        val imageData = loadImageData()

        startTime = System.currentTimeMillis()
        val packet = DatagramPacket(imageData, imageData.size, address, serverPort)
        socket.send(packet)
        socket.close()

        Log.d("UDP", "UDP Image sent successfully in ${System.currentTimeMillis() - startTime} ms")
    } catch (e: IOException) {
        Log.e("UDP", "UDP Error: ${e.message}")
    }
}
fun sendImageRUDP(serverAddress: String, serverPort: Int) {
    try {
        val socket = DatagramSocket()
        val address = InetAddress.getByName(serverAddress)
        val imageData = loadImageData()

        var sequenceNumber = 0
        val packetSize = 1024
        var offset = 0
        val ackTimeout = 2000 // ms

        startTime = System.currentTimeMillis()

        while (offset < imageData.size) {
            val end = minOf(offset + packetSize, imageData.size)
            val packetData = ByteArray(end - offset + 4)
            packetData[0] = (sequenceNumber shr 24).toByte()
            packetData[1] = (sequenceNumber shr 16).toByte()
            packetData[2] = (sequenceNumber shr 8).toByte()
            packetData[3] = sequenceNumber.toByte()
            System.arraycopy(imageData, offset, packetData, 4, end - offset)

            val packet = DatagramPacket(packetData, packetData.size, address, serverPort)
            socket.send(packet)

            // Acknowledgment handling
            try {
                socket.soTimeout = ackTimeout
                val ackData = ByteArray(4)
                val ackPacket = DatagramPacket(ackData, ackData.size)
                socket.receive(ackPacket)

                val ackSequence = (ackData[0].toInt() and 0xFF shl 24) or
                        (ackData[1].toInt() and 0xFF shl 16) or
                        (ackData[2].toInt() and 0xFF shl 8) or
                        (ackData[3].toInt() and 0xFF)

                if (ackSequence == sequenceNumber) {
                    // Move to the next packet
                    offset = end
                    sequenceNumber++
                } else {
                    Log.w("RUDP", "RUDP Ack mismatch, retransmitting...")
                }
            } catch (e: SocketTimeoutException) {
                Log.w("RUDP", "RUDP Ack timeout, retransmitting packet $sequenceNumber...")
            }
        }

        socket.close()
        Log.d("RUDP", "RUDP Image sent successfully in ${System.currentTimeMillis() - startTime} ms")
    } catch (e: IOException) {
        Log.e("RUDP", "RUDP Error: ${e.message}")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SocketTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        sendImageTCP(serverAddress, serverPort)
        sendImageUDP(serverAddress, serverPort)
        sendImageRUDP(serverAddress, serverPort)

    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SocketTheme {
        Greeting("Android")
    }
}