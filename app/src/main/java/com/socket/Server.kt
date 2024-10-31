// Server.kt
package com.socket

import java.io.*
import java.net.*
import java.nio.ByteBuffer

class Server {
    private val tcpPort = 12345
    private val udpPort = 12346
    private val rudpPort = 12347
    private var isRunning = true

    fun start() {
        Thread { handleTCPConnections() }.start()
        Thread { handleUDPConnections() }.start()
        Thread { handleRUDPConnections() }.start()
        println("Server started on ports - TCP: $tcpPort, UDP: $udpPort, RUDP: $rudpPort")
    }

    private fun handleTCPConnections() {
        ServerSocket(tcpPort).use { serverSocket ->
            while (isRunning) {
                try {
                    val socket = serverSocket.accept()
                    Thread { handleTCPClient(socket) }.start()
                } catch (e: IOException) {
                    println("TCP Server error: ${e.message}")
                }
            }
        }
    }

    private fun handleTCPClient(socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // 데이터 크기 읽기
            val sizeBuffer = ByteArray(4)
            inputStream.read(sizeBuffer)
            val size = ByteBuffer.wrap(sizeBuffer).int

            // 데이터 읽기
            var bytesRead = 0
            val buffer = ByteArray(1024)
            while (bytesRead < size) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                bytesRead += count
            }

            // 완료 응답 전송
            outputStream.write("DONE".toByteArray())
            outputStream.flush()

            socket.close()
            println("TCP: Received ${bytesRead} bytes")
        } catch (e: IOException) {
            println("TCP Client error: ${e.message}")
        }
    }

    private fun handleUDPConnections() {
        DatagramSocket(udpPort).use { socket ->
            val buffer = ByteArray(65507) // UDP 최대 크기

            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    // 헤더 패킷인지 확인
                    val data = packet.data
                    val marker = ByteBuffer.wrap(data, 0, 4).int

                    when (marker) {
                        -1 -> { // 시작 마커
                            val totalChunks = ByteBuffer.wrap(data, 4, 4).int
                            val totalSize = ByteBuffer.wrap(data, 8, 4).int
                            println("UDP: Starting transfer of $totalSize bytes in $totalChunks chunks")
                        }
                        -2 -> { // 종료 마커
                            val response = "DONE".toByteArray()
                            socket.send(DatagramPacket(response, response.size,
                                packet.address, packet.port))
                            println("UDP: Transfer complete")
                        }
                        else -> { // 데이터 청크
                            val chunkSize = ByteBuffer.wrap(data, 4, 4).int
                            println("UDP: Received chunk $marker of size $chunkSize")
                        }
                    }
                } catch (e: IOException) {
                    println("UDP Server error: ${e.message}")
                }
            }
        }
    }

    private fun handleRUDPConnections() {
        DatagramSocket(rudpPort).use { socket ->
            val buffer = ByteArray(2048)

            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val data = packet.data
                    val marker = ByteBuffer.wrap(data, 0, 4).int

                    when (marker) {
                        -1 -> { // 초기화 패킷
                            val response = "INIT".toByteArray()
                            socket.send(DatagramPacket(response, response.size,
                                packet.address, packet.port))
                        }
                        -2 -> { // 종료 패킷
                            val response = "FACK".toByteArray()
                            socket.send(DatagramPacket(response, response.size,
                                packet.address, packet.port))
                        }
                        else -> { // 데이터 패킷
                            val ackPacket = ByteBuffer.allocate(4).putInt(marker).array()
                            socket.send(DatagramPacket(ackPacket, ackPacket.size,
                                packet.address, packet.port))
                        }
                    }
                } catch (e: IOException) {
                    println("RUDP Server error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
    }
}

fun main() {
    val server = Server()
    server.start()
    println("Press Enter to stop the server...")
    readLine()
    server.stop()
}