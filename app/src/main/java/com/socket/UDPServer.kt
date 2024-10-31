package com.socket

// UDP 서버
import java.net.DatagramPacket
import java.net.DatagramSocket

fun main() {
    val server = UdpServer(12345)
    server.start()
}

class UdpServer(private val port: Int) {
    fun start() {
        val socket = DatagramSocket(port)
        println("UDP 서버가 포트 $port 에서 시작되었습니다...")

        val buffer = ByteArray(1024)

        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)

            val received = String(packet.data, 0, packet.length)
            println("받은 메시지: $received")

            // 응답 보내기
            val responseData = "메시지 수신 완료".toByteArray()
            val responsePacket = DatagramPacket(
                responseData,
                responseData.size,
                packet.address,
                packet.port
            )
            socket.send(responsePacket)
        }
    }
}