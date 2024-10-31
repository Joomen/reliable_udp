import com.socket.ReliableImageSender
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress

fun main() = runBlocking {
    // 테스트할 이미지 파일 경로 설정
    val imageFile = File("C:\\Users\\user\\Desktop\\reliable_udp\\app\\src\\main\\java\\com\\socket\\DJI_20240404115702_0007_W.JPG") // 이미지 파일 경로를 적절히 수정하세요
    if (!imageFile.exists()) {
        println("이미지 파일을 찾을 수 없습니다: ${imageFile.absolutePath}")
        return@runBlocking
    }

    // 이미지 파일 읽기
    val imageData = imageFile.readBytes()
    println("이미지 크기: ${imageData.size} bytes")

    // com.socket.ReliableImageSender 인스턴스 생성
    val sender = ReliableImageSender(chunkSize = 60000) // 청크 크기는 필요에 따라 조정 가능

    try {
        // 수신자 주소 설정 (localhost:50000 사용)
        val receiverAddress = InetSocketAddress("127.0.0.1", 50001)
        println("이미지 전송 시작... (수신자 주소: $receiverAddress)")

        // 이미지 전송
        val sendJob = launch {
            sender.sendImage(imageData, receiverAddress)
        }
        println("전송 프로세스 시작됨")

        sendJob.join()


        // 메인 스레드가 즉시 종료되지 않도록 대기
        while (true) {
            Thread.sleep(1000)
        }
    } catch (e: Exception) {
        println("전송 중 오류 발생: ${e.message}")
        e.printStackTrace()
    }
}