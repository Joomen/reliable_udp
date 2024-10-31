import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

data class PacketHeader(
    val sequenceNumber: Int,
    val biggestSequenceNumber: Int
)

data class AckPacket(
    val missingSequences: List<Int>
)

class ReliableImageSender(
    private val chunkSize: Int = 60000,
    private val timeout: Long = 5 // 타임아웃 시간(초)
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val packetBuffer = mutableMapOf<Int, ByteArray>()
    private var isRunning = true
    private var currentBiggestSequence = 0
    private var lastPacketSent = false

    fun sendImage(imageData: ByteArray, address: InetSocketAddress) {
        val socket = DatagramSocket()
        val totalChunks = (imageData.size + chunkSize - 1) / chunkSize
        currentBiggestSequence = totalChunks - 1

        // ACK 수신 처리 코루틴
        scope.launch {
            receiveAcks(socket, address)
        }

        // 패킷 전송 코루틴
        scope.launch {
            sendPackets(socket, imageData, address)
        }
    }

    fun close() {
        scope.cancel()
        isRunning = false
    }

    private suspend fun sendPackets(
        socket: DatagramSocket,
        imageData: ByteArray,
        address: InetSocketAddress
    ) {
        try {
            var currentSequence = 0

            while (isRunning && currentSequence <= currentBiggestSequence) {
                val start = currentSequence * chunkSize
                val end = minOf(start + chunkSize, imageData.size)
                val chunk = imageData.copyOfRange(start, end)

                // 패킷 생성 및 전송
                val packetData = createPacketData(chunk, currentSequence, currentBiggestSequence)
                val packet = DatagramPacket(packetData, packetData.size, address)

                // 버퍼에 청크 저장
                packetBuffer[currentSequence] = chunk

                socket.send(packet)
                println("Chunk ${currentSequence} 전송")
                currentSequence++
            }
        } finally {
            isRunning = false
        }
    }

    private suspend fun receiveAcks(socket: DatagramSocket, address: InetSocketAddress) {
        val buffer = ByteArray(1024)
        val receivePacket = DatagramPacket(buffer, buffer.size)
        var retryCount = 0
        val maxRetries = 3  // 최대 재시도 횟수

        socket.soTimeout = 3000
        while (true) {
            try {
                withTimeout(timeout.seconds) {
                    socket.receive(receivePacket)
                    // ACK 패킷 파싱
                    val data = receivePacket.data.copyOfRange(0, receivePacket.length)
                    val missingSequences = parseMissingSequences(data)

                    if (missingSequences.isEmpty()) {
                        // 모든 패킷이 성공적으로 전송됨
                        println("전송 완료")
                        isRunning = false
                        return@withTimeout
                    }

                    // 일반적인 재전송
                    println("재전송 필요한 시퀀스: $missingSequences")
                    resendMissingPackets(socket, address, missingSequences)
                }
            } catch (e: TimeoutCancellationException) {
                println("타임아웃 발생: 마지막 패킷 재전송")
                if (!lastPacketSent) {
                    resendLastPacket(socket, address)
                    lastPacketSent = true
                    retryCount++

                    if (retryCount >= maxRetries) {
                        println("최대 재시도 횟수 초과")
                        isRunning = false
                    }
                }
            } catch (e: Exception) {
                println("ACK 수신 오류: ${e.message}")
            } finally {
            }
        }
    }

    private fun parseMissingSequences(data: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(data)
        val count = buffer.getInt()
        return (0 until count).map { buffer.getInt() }
    }

    private suspend fun resendLastPacket(
        socket: DatagramSocket,
        address: InetSocketAddress
    ) {
        packetBuffer[currentBiggestSequence]?.let { chunk ->
            val packetData = createPacketData(chunk, currentBiggestSequence, currentBiggestSequence)
            val packet = DatagramPacket(packetData, packetData.size, address)
            socket.send(packet)
            println("마지막 Chunk $currentBiggestSequence 재전송")
        }
    }

    private suspend fun resendMissingPackets(
        socket: DatagramSocket,
        address: InetSocketAddress,
        missingSequences: List<Int>
    ) {
        currentBiggestSequence = missingSequences.maxOrNull()!!
        for (seqNum in missingSequences) {
            packetBuffer[seqNum]?.let { chunk ->
                val packetData = createPacketData(chunk, seqNum, currentBiggestSequence)
                val packet = DatagramPacket(packetData, packetData.size, address)
                socket.send(packet)
                println("Chunk $seqNum 재전송")
            }
        }
    }

    private fun createPacketData(
        chunk: ByteArray,
        sequenceNumber: Int,
        biggestSequenceNumber: Int
    ): ByteArray {
        return ByteBuffer.allocate(8 + chunk.size).apply {
            putInt(sequenceNumber)
            putInt(biggestSequenceNumber)
            put(chunk)
        }.array()
    }
}

