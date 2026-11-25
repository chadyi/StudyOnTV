# StudyOnTV

Một ứng dụng Android phục vụ học tập và quản lý thời gian sử dụng màn hình cho máy tính bảng, điện thoại và TV. Máy tính bảng và điện thoại dùng APK Mobile; TV dùng một APK TV nhẹ riêng. Cả hai đều lưu ngân hàng câu hỏi, tiến độ và bản ghi sử dụng ngay trên thiết bị.

Dự án này liên kết với và ủng hộ cộng đồng [LINUX DO](https://linux.do).

[Tải từ GitHub Release](https://github.com/chadyi/StudyOnTV/releases/latest) · [中文说明](./README.md) · [English](./README.en.md) · [Cài đặt](./docs/install.md)

Nếu không truy cập được GitHub, hãy dùng bản dự phòng Pgyer: [Mobile / máy tính bảng](https://www.pgyer.com/StudyOnTVMobile) · [TV](https://www.pgyer.com/StudyOnTVTV).

## Học tập

- Câu hỏi trắc nghiệm một lựa chọn, nhiều lựa chọn, đúng/sai, trả lời ngắn và tự luận.
- Công thức LaTeX ngoại tuyến nhờ KaTeX và phông chữ đóng gói sẵn.
- Nhập liệu bằng bàn phím, chuyển giọng nói thành văn bản của hệ thống và ảnh chụp chữ viết tay.
- Bài tập theo vòng, chuỗi trả lời đúng liên tiếp và luyện tập không giới hạn.
- Phần thưởng tùy chỉnh cho câu hỏi được xếp hạng 1–5 sao.
- Phản hồi ngay dưới mỗi câu hỏi: đúng hay sai, đáp án đúng và lời giải thích; trẻ tiếp tục khi đã sẵn sàng. Phụ huynh có thể chỉnh sửa các câu khích lệ.
- Giao diện TV thân thiện với điều khiển từ xa, tiêu điểm kiểu Netflix, chế độ tối và sáng, nhập đáp án bằng bàn phím hệ thống hoặc giọng nói. Video phía sau lớp học tập được tạm dừng.
- Tùy chọn ôn tập ngắt quãng (spaced repetition) ưu tiên các câu đến hạn và giữ tiến độ học qua các lần khởi động lại.
- Nhóm luyện thêm bền vững: trả lời sai sẽ thêm câu hỏi khi đạt ngưỡng tùy chỉnh; trả lời đúng trong phần luyện thêm sẽ hoàn lại nhóm. Khởi động lại hay thay đổi ngày tháng không xóa nhóm này.

## Quản lý dành cho phụ huynh

Thiết bị được quản lý tự chạy một bảng điều khiển HTTP cục bộ. Phụ huynh kết nối qua cùng một mạng và nhập mật khẩu quản lý đã đặt khi thiết lập ban đầu. Chạm vào tiêu đề ứng dụng năm lần, hoặc nhấn phím điều hướng Lên năm lần khi tiêu điểm đang ở trên cùng, để mở lại màn hình kết nối được bảo vệ bằng mật khẩu.

Bảng điều khiển tách riêng quy tắc, ngân hàng câu hỏi, chấm điểm và bản ghi sử dụng. Các API cũng yêu cầu xác thực; mã truy cập sáu chữ số trước đây và quyền truy cập ngân hàng câu hỏi không cần xác thực đã bị loại bỏ.

Bài tự luận có thể được chấm bởi phụ huynh, một Agent đã xác thực, hoặc một endpoint AI tương thích OpenAI tùy chọn như DeepSeek, Qwen, Zhipu, Kimi, SiliconFlow hay một máy chủ Ollama cục bộ, với các preset tích hợp sẵn, kiểm tra kết nối và lấy danh sách mô hình. Chữ viết tay cần một mô hình có khả năng nhìn (vision) hoặc người chấm. Lệnh gọi AI thất bại sẽ giữ lại bài nộp để xem xét sau. Bật AI đồng nghĩa với việc gửi nội dung câu hỏi và câu trả lời tới nhà cung cấp đã cấu hình.

[API ngân hàng câu hỏi và chấm điểm](./docs/ai-api.md) · [Quy tắc chơi](./docs/gameplay.md) · [Ngân hàng câu hỏi mẫu](./data/banks/open-play.json) · [Tài liệu định dạng câu hỏi](./docs/question-format.md)

## Ảnh chụp màn hình

![Học trên máy tính bảng](./docs/screenshots/v0.3.0/tablet-quiz.png)
![Viết tay trên máy tính bảng](./docs/screenshots/v0.3.0/tablet-handwriting.png)
<table><tr>
<td align="center"><img src="./docs/screenshots/v0.3.0/phone-quiz.png" width="280" alt="Học trên điện thoại"><br><sub>Học trên điện thoại</sub></td>
<td align="center"><img src="./docs/screenshots/v0.3.0/parent-review.png" width="280" alt="Phụ huynh xem lại"><br><sub>Phụ huynh xem lại và cài đặt AI</sub></td>
</tr></table>
![Bảng điều khiển của phụ huynh](./docs/screenshots/v0.3.0/parent-desktop.png)
![Học trên TV](./docs/screenshots/v0.3.0/tv-quiz.png)

## Giới hạn thời gian màn hình

Mã Android gốc kiểm tra đáp án và trao thời gian. Thời gian dùng ứng dụng ở tiền cảnh thực tế mới trừ vào số dư đã lưu; thời gian học, ở trình khởi chạy, màn hình khóa và màn hình tắt không bị trừ. Các giới hạn gồm thời gian dùng mỗi ngày, số vòng được thưởng mỗi ngày và khung giờ cho phép, kể cả khung giờ qua đêm.

Lịch trình có thể chỉ cho phép dùng thiết bị trong các giờ đã chọn, hoặc chỉ yêu cầu học trong các giờ đó. Cách thứ hai để phần còn lại của ngày tự do, không trừ vào số dư đã kiếm được. Cả hai đều hỗ trợ khung giờ qua đêm và tùy chọn cả ngày rõ ràng.

Mọi quyền đều là tùy chọn. Quyền trợ năng, hoặc quyền hiển thị trên ứng dụng khác kết hợp với quyền truy cập sử dụng, có thể nhận diện ứng dụng và thực thi giới hạn. Chế độ chỉ-hiển thị-phủ ước lượng thời gian thức ngoài màn hình học. Trong các giai đoạn bị kiểm soát, phụ huynh có thể bật chặn Cài đặt; xác minh phụ huynh cục bộ tạm thời cho phép truy cập, và các giai đoạn tự do vẫn không bị hạn chế. Tùy chọn kích hoạt quản trị thiết bị giúp ngăn gỡ cài đặt thông thường trên các thiết bị được hỗ trợ. Dừng bắt buộc, thu hồi quyền và chính sách chạy nền của nhà sản xuất có thể làm gián đoạn việc thực thi.

Khởi động Android, một số sự kiện phát sóng khởi động nhanh được hỗ trợ và thay thế gói ứng dụng có thể khởi động lại dịch vụ quản lý sau khi thiết lập ban đầu. Thao tác chờ bằng điều khiển TV hoặc dọn dẹp tác vụ của nhà sản xuất có thể không gửi sự kiện khởi động; hãy cho phép tự khởi động/chạy nền và kiểm tra hành vi trên thiết bị thực tế.

## Góp ý từ cộng đồng

Cảm ơn **xiuweng** trên LINUX DO về điều khiển trên mobile, lối vào quản lý ẩn và luyện thêm bền vững; và **free-swimming** về độ khó theo sao và phần thưởng thời gian khác nhau.

Các góp ý từ [#2](https://github.com/chadyi/StudyOnTV/issues/2) đã cải thiện đổi mật khẩu, preset AI, quản lý ngân hàng câu hỏi và tải lười (lazy loading). [#3](https://github.com/chadyi/StudyOnTV/issues/3) dẫn tới phản hồi đáp án ngay trong dòng và tiếp tục thủ công; [#4](https://github.com/chadyi/StudyOnTV/issues/4) định hướng lịch giờ kiểm soát, bảo vệ gỡ cài đặt tùy chọn và xử lý âm thanh nền mạnh hơn. [#5](https://github.com/chadyi/StudyOnTV/issues/5) thúc đẩy hướng dẫn khởi động cùng hệ thống rõ ràng hơn.

## Giấy phép

Mã nguồn được cung cấp cho mục đích sử dụng cá nhân, phi thương mại trong gia đình theo [LICENSE](./LICENSE). Dự án này không dùng giấy phép MIT. Các thành phần của bên thứ ba giữ giấy phép riêng của chúng; xem [thông báo bên thứ ba](./THIRD_PARTY_NOTICES.md).
