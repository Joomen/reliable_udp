// Client.kt
package com.socket

import java.io.*
import java.net.*
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

data class ProtocolMetrics(
    val setupTime: Long = 0,      // 연결 수립 시간
    val transferTime: Long = 0,    // 전송 시간
    val teardownTime: Long = 0,    // 종료 시간
    val totalTime: Long           // 전체 시간
)

class Client {
    private val serverAddress = "localhost"
    private val tcpPort = 12345
    private val udpPort = 12346
    private val rudpPort = 12347
    private val IMAGE_PATH = "C:\\Users\\user\\Downloads\\12.jpg"
    private val NUMBER_OF_TRIALS = 5
    private val TIMEOUT = 5000

    private fun loadImageData(): ByteArray = File(IMAGE_PATH).readBytes()

    private fun measureProtocol(
        protocol: String,
        action: () -> ProtocolMetrics
    ): List<ProtocolMetrics> {
        return List(NUMBER_OF_TRIALS) { trial ->
            println("$protocol - Trial ${trial + 1}/$NUMBER_OF_TRIALS")
            Thread.sleep(1000)
            action()
        }
    }

    private fun sendTCP(): ProtocolMetrics {
        var setupTime = 0L
        var transferTime = 0L
        var teardownTime = 0L

        val totalTime = measureTimeMillis {
            try {
                setupTime = measureTimeMillis {
                    Socket(serverAddress, tcpPort) // 3-way handshake
                }

                Socket(serverAddress, tcpPort).use { socket ->
                    socket.soTimeout = TIMEOUT
                    transferTime = measureTimeMillis {
                        val outputStream = socket.getOutputStream()
                        val inputStream = socket.getInputStream()
                        val imageData = loadImageData()

                        outputStream.write(ByteBuffer.allocate(4).putInt(imageData.size).array())
                        outputStream.write(imageData)
                        outputStream.flush()

                        val response = ByteArray(4)
                        inputStream.read(response)
                        require(response.contentEquals("DONE".toByteArray()))
                    }

                    teardownTime = measureTimeMillis {
                        socket.shutdownOutput()
                        socket.shutdownInput()
                    }
                }
            } catch (e: Exception) {
                println("TCP Error: ${e.message}")
                throw e
            }
        }

        return ProtocolMetrics(setupTime, transferTime, teardownTime, totalTime)
    }

    private fun sendUDP(): ProtocolMetrics {
        val totalTime = measureTimeMillis {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = TIMEOUT
                    val address = InetAddress.getByName(serverAddress)
                    val imageData = loadImageData()
                    val CHUNK_SIZE = 60000

                    // 시작 패킷 전송
                    val totalChunks = (imageData.size + CHUNK_SIZE - 1) / CHUNK_SIZE
                    val startHeader = ByteBuffer.allocate(12)
                        .putInt(-1)
                        .putInt(totalChunks)
                        .putInt(imageData.size)
                        .array()
                    socket.send(DatagramPacket(startHeader, startHeader.size, address, udpPort))

                    // 데이터 전송
                    for (i in 0 until totalChunks) {
                        val start = i * CHUNK_SIZE
                        val end = minOf(start + CHUNK_SIZE, imageData.size)
                        val chunkSize = end - start

                        val packetData = ByteArray(8 + chunkSize).apply {
                            System.arraycopy(
                                ByteBuffer.allocate(8)
                                    .putInt(i)
                                    .putInt(chunkSize)
                                    .array(),
                                0, this, 0, 8
                            )
                            System.arraycopy(imageData, start, this, 8, chunkSize)
                        }

                        socket.send(DatagramPacket(packetData, packetData.size, address, udpPort))
                    }

                    // 종료 패킷 전송
                    val endMarker = ByteBuffer.allocate(4).putInt(-2).array()
                    socket.send(DatagramPacket(endMarker, endMarker.size, address, udpPort))

                    try {
                        val response = ByteArray(4)
                        socket.receive(DatagramPacket(response, response.size))
                    } catch (e: SocketTimeoutException) {
                        println("UDP completion response timeout (expected)")
                    }
                }
            } catch (e: Exception) {
                println("UDP Error: ${e.message}")
                throw e
            }
        }

        return ProtocolMetrics(0, totalTime, 0, totalTime)
    }

    private fun sendRUDP(): ProtocolMetrics {
        var setupTime = 0L
        var transferTime = 0L
        var teardownTime = 0L

        val totalTime = measureTimeMillis {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = TIMEOUT
                    val address = InetAddress.getByName(serverAddress)
                    val imageData = loadImageData()

                    // 초기화
                    setupTime = measureTimeMillis {
                        val initPacket = ByteBuffer.allocate(8)
                            .putInt(-1)
                            .putInt(imageData.size)
                            .array()

                        repeat(5) {
                            socket.send(
                                DatagramPacket(
                                    initPacket,
                                    initPacket.size,
                                    address,
                                    rudpPort
                                )
                            )
                            try {
                                val response = ByteArray(4)
                                socket.receive(DatagramPacket(response, response.size))
                                if (response.contentEquals("INIT".toByteArray())) return@measureTimeMillis
                            } catch (e: SocketTimeoutException) {
                            }
                        }
                        throw IOException("RUDP initialization failed")
                    }

                    // 데이터 전송
                    transferTime = measureTimeMillis {
                        var offset = 0
                        var sequenceNumber = 0
                        val packetSize = 1024

                        while (offset < imageData.size) {
                            val end = minOf(offset + packetSize, imageData.size)
                            val packetData = ByteArray(end - offset + 4).apply {
                                System.arraycopy(
                                    ByteBuffer.allocate(4).putInt(sequenceNumber).array(),
                                    0, this, 0, 4
                                )
                                System.arraycopy(imageData, offset, this, 4, end - offset)
                            }

                            repeat(5) {
                                socket.send(
                                    DatagramPacket(
                                        packetData,
                                        packetData.size,
                                        address,
                                        rudpPort
                                    )
                                )
                                try {
                                    val ackData = ByteArray(4)
                                    socket.receive(DatagramPacket(ackData, ackData.size))
                                    if (ByteBuffer.wrap(ackData).int == sequenceNumber) {
                                        offset = end
                                        sequenceNumber++
                                        return@repeat
                                    }
                                } catch (e: SocketTimeoutException) {
                                }
                            }
                            if (offset < end) throw IOException("Failed to send packet $sequenceNumber")
                        }
                    }

                    // 종료
                    teardownTime = measureTimeMillis {
                        val finPacket = ByteBuffer.allocate(4).putInt(-2).array()
                        repeat(5) {
                            socket.send(
                                DatagramPacket(
                                    finPacket,
                                    finPacket.size,
                                    address,
                                    rudpPort
                                )
                            )
                            try {
                                val response = ByteArray(4)
                                socket.receive(DatagramPacket(response, response.size))
                                if (response.contentEquals("FACK".toByteArray())) return@measureTimeMillis
                            } catch (e: SocketTimeoutException) {
                            }
                        }
                        throw IOException("RUDP termination failed")
                    }
                }
            } catch (e: Exception) {
                println("RUDP Error: ${e.message}")
                throw e
            }
        }

        return ProtocolMetrics(setupTime, transferTime, teardownTime, totalTime)
    }

    fun runTest() {
        println("Starting protocol performance test...")
        println("Image: $IMAGE_PATH")
        println("Server: $serverAddress (TCP: $tcpPort, UDP: $udpPort, RUDP: $rudpPort)")
        println("Trials: $NUMBER_OF_TRIALS")
        println("----------------------------------------")

        listOf(
            Triple("TCP", ::sendTCP, tcpPort),
            Triple("UDP", ::sendUDP, udpPort),
            Triple("RUDP", ::sendRUDP, rudpPort)
        ).forEach { (name, sender, port) ->
            println("Testing $name on port $port...")
            val metrics = measureProtocol(name, sender)
            printResults(name, metrics)
        }
    }

    // Client.kt의 printResults 함수 완성 버전
    private fun printResults(protocol: String, metrics: List<ProtocolMetrics>) {
        val setup = metrics.map { it.setupTime }
        val transfer = metrics.map { it.transferTime }
        val teardown = metrics.map { it.teardownTime }
        val total = metrics.map { it.totalTime }

        println(
            """
       |$protocol Results:
       |Setup Time: avg=${
                setup.average().toInt()
            }ms min=${setup.minOrNull()}ms max=${setup.maxOrNull()}ms
       |Transfer Time: avg=${
                transfer.average().toInt()
            }ms min=${transfer.minOrNull()}ms max=${transfer.maxOrNull()}ms
       |Teardown Time: avg=${
                teardown.average().toInt()
            }ms min=${teardown.minOrNull()}ms max=${teardown.maxOrNull()}ms
       |Total Time: avg=${
                total.average().toInt()
            }ms min=${total.minOrNull()}ms max=${total.maxOrNull()}ms
       |
       |Percentage Breakdown:
       |Setup: ${(setup.average() / total.average() * 100).toInt()}%
       |Transfer: ${(transfer.average() / total.average() * 100).toInt()}%
       |Teardown: ${(teardown.average() / total.average() * 100).toInt()}%
       |----------------------------------------
       """.trimMargin()
        )
    }
}

    // main 함수 추가
    fun main() {
        try {
            // 서버가 실행 중인지 확인
            val testSocket = Socket("localhost", 12345)
            testSocket.close()

            // 서버가 실행 중이면 클라이언트 테스트 시작
            val client = Client()
            client.runTest()
        } catch (e: ConnectException) {
            println("Error: Server is not running. Please start the server first.")
            println("Make sure the server is running on ports TCP:12345, UDP:12346, and RUDP:12347")
        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }