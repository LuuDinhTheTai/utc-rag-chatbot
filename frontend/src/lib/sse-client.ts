import type { Source } from "@/types/chat";

export interface SSECallbacks {
  onContent: (chunk: string) => void;
  onSources: (sources: Source[]) => void;
  onDone: () => void;
  onError: (error: string) => void;
}

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL || "http://localhost:8000";

/**
 * SSE client using fetch + ReadableStream for streaming chat responses.
 * Returns an AbortController so the caller can cancel the stream.
 */
export function sendSSEMessage(
  message: string,
  conversationId: string,
  callbacks: SSECallbacks
): AbortController {
  const controller = new AbortController();

  (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/chat`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream",
        },
        body: JSON.stringify({
          message,
          conversation_id: conversationId,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        callbacks.onError(`Server error: ${response.status}`);
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        callbacks.onError("No response body");
        return;
      }

      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";

        for (const line of lines) {
          if (line.startsWith("data: ")) {
            const data = line.slice(6).trim();
            if (data === "[DONE]") {
              callbacks.onDone();
              return;
            }

            try {
              const parsed = JSON.parse(data);
              switch (parsed.type) {
                case "content":
                  callbacks.onContent(parsed.data);
                  break;
                case "sources":
                  callbacks.onSources(parsed.sources || []);
                  break;
                case "error":
                  callbacks.onError(parsed.data || "Unknown error");
                  break;
                case "done":
                  callbacks.onDone();
                  return;
              }
            } catch {
              // If not valid JSON, treat as plain text content
              if (data) {
                callbacks.onContent(data);
              }
            }
          }
        }
      }

      // Stream ended without explicit done signal
      callbacks.onDone();
    } catch (error: unknown) {
      if (error instanceof Error && error.name === "AbortError") {
        // Request was cancelled, do nothing
        return;
      }
      callbacks.onError(
        error instanceof Error ? error.message : "Connection failed"
      );
    }
  })();

  return controller;
}

/**
 * Mock SSE for development/demo when backend is not available.
 * Simulates streaming response with realistic timing.
 */
export function sendMockSSEMessage(
  message: string,
  _conversationId: string,
  callbacks: SSECallbacks
): AbortController {
  const controller = new AbortController();

  const responses: Record<string, { text: string; sources: Source[] }> = {
    default: {
      text: `Cảm ơn bạn đã quan tâm đến **Trường Đại học Giao thông Vận tải (UTC)**! 🎓

Trường Đại học Giao thông Vận tải là một trong những trường đại học hàng đầu Việt Nam trong lĩnh vực kỹ thuật giao thông vận tải, xây dựng và công nghệ thông tin.

### 📍 Thông tin chung
- **Địa chỉ:** Số 3 phố Cầu Giấy, phường Láng Thượng, quận Đống Đa, Hà Nội
- **Website:** [utc.edu.vn](https://utc.edu.vn)
- **Điện thoại tuyển sinh:** 024.3834.5614

### 🏫 Các ngành đào tạo nổi bật
1. Kỹ thuật Xây dựng Công trình Giao thông
2. Kỹ thuật Xây dựng
3. Công nghệ Thông tin
4. Kỹ thuật Ô tô
5. Logistics và Quản lý Chuỗi cung ứng
6. Kỹ thuật Điện - Điện tử
7. Kinh tế Vận tải

Bạn muốn tìm hiểu thêm về ngành nào cụ thể không? 😊`,
      sources: [
        {
          title: "Thông tin tuyển sinh UTC 2025",
          content:
            "Trường Đại học Giao thông Vận tải tuyển sinh 4500 chỉ tiêu năm 2025 với 35 ngành đào tạo...",
          score: 0.95,
        },
        {
          title: "Giới thiệu về UTC",
          content:
            "Trường Đại học Giao thông Vận tải được thành lập năm 1962, là trường đại học kỹ thuật hàng đầu...",
          score: 0.89,
        },
      ],
    },
  };

  const lowerMessage = message.toLowerCase();
  let responseData = responses.default;

  if (lowerMessage.includes("điểm chuẩn") || lowerMessage.includes("diem chuan")) {
    responseData = {
      text: `## 📊 Điểm chuẩn các ngành năm 2025 - ĐH Giao thông Vận tải

Dưới đây là điểm chuẩn một số ngành tiêu biểu theo phương thức **xét điểm thi tốt nghiệp THPT**:

| STT | Ngành | Mã ngành | Điểm chuẩn |
|-----|-------|----------|-------------|
| 1 | Công nghệ Thông tin | 7480201 | **26.50** |
| 2 | Kỹ thuật Xây dựng | 7580201 | **24.00** |
| 3 | Kỹ thuật Ô tô | 7520130 | **25.75** |
| 4 | Logistics và QLCCƯ | 7510605 | **26.00** |
| 5 | Kỹ thuật Xây dựng CTGT | 7580205 | **23.50** |
| 6 | Kiến trúc | 7580101 | **25.00** |
| 7 | Kỹ thuật Điện - Điện tử | 7520201 | **24.50** |
| 8 | Kinh tế Vận tải | 7840104 | **24.25** |

> **Lưu ý:** Điểm chuẩn trên là tham khảo. Điểm chuẩn thực tế có thể thay đổi tùy từng năm.

Bạn muốn biết thêm chi tiết về ngành nào không? 🎯`,
      sources: [
        {
          title: "Điểm chuẩn UTC 2025",
          content:
            "Bảng điểm chuẩn xét tuyển đại học năm 2025 theo phương thức thi tốt nghiệp THPT...",
          score: 0.97,
        },
      ],
    };
  } else if (lowerMessage.includes("học phí") || lowerMessage.includes("hoc phi")) {
    responseData = {
      text: `## 💰 Học phí Trường ĐH Giao thông Vận tải

### Năm học 2025-2026

Học phí được áp dụng theo tín chỉ, mức thu cụ thể như sau:

| Nhóm ngành | Học phí/tín chỉ | Ước tính/năm |
|------------|-----------------|--------------|
| Kỹ thuật - Công nghệ | **380.000 - 450.000đ** | ~15 - 18 triệu |
| Công nghệ Thông tin | **420.000 - 480.000đ** | ~17 - 19 triệu |
| Kinh tế - Quản lý | **350.000 - 420.000đ** | ~14 - 17 triệu |
| Chương trình CLC | **600.000 - 800.000đ** | ~24 - 32 triệu |

### 🎓 Chính sách hỗ trợ
- **Học bổng khuyến khích:** 20-100% học phí cho sinh viên có thành tích xuất sắc
- **Miễn giảm học phí:** Theo chính sách của Nhà nước
- **Hỗ trợ vay vốn:** Liên kết với Ngân hàng Chính sách Xã hội

> Bạn có thể liên hệ Phòng Công tác Sinh viên để biết thêm chi tiết.`,
      sources: [
        {
          title: "Quy định học phí UTC 2025-2026",
          content:
            "Mức thu học phí áp dụng cho sinh viên khóa mới năm học 2025-2026...",
          score: 0.94,
        },
      ],
    };
  } else if (
    lowerMessage.includes("xét tuyển") ||
    lowerMessage.includes("xet tuyen") ||
    lowerMessage.includes("điều kiện") ||
    lowerMessage.includes("dieu kien")
  ) {
    responseData = {
      text: `## 📋 Phương thức xét tuyển ĐH Giao thông Vận tải 2025

Trường UTC áp dụng **4 phương thức** xét tuyển:

### 1️⃣ Xét điểm thi tốt nghiệp THPT
- Sử dụng kết quả thi tốt nghiệp THPT năm 2025
- Các tổ hợp: **A00, A01, B00, D01, D07**
- Điểm xét = Tổng 3 môn + Điểm ưu tiên

### 2️⃣ Xét học bạ THPT
- Điều kiện: Tốt nghiệp THPT
- Xét tổng điểm trung bình 3 môn trong tổ hợp (5 học kỳ hoặc cả năm lớp 12)
- Ngưỡng đảm bảo chất lượng: **21.00 điểm**

### 3️⃣ Xét tuyển thẳng
- Thí sinh đạt giải Quốc gia, Quốc tế
- Theo quy định của Bộ GD&ĐT

### 4️⃣ Xét điểm thi đánh giá năng lực
- Chấp nhận kết quả kỳ thi ĐGNL của ĐHQG Hà Nội
- Ngưỡng tối thiểu: **80/150 điểm**

### 📅 Thời gian xét tuyển
- Đợt 1 (Học bạ): 01/03 - 30/06/2025
- Đợt 2 (Thi TN THPT): Theo lịch Bộ GD&ĐT

Bạn cần tư vấn cụ thể hơn về phương thức nào? 📝`,
      sources: [
        {
          title: "Đề án tuyển sinh UTC 2025",
          content:
            "Trường Đại học Giao thông Vận tải công bố đề án tuyển sinh đại học chính quy năm 2025...",
          score: 0.96,
        },
        {
          title: "Thông báo xét tuyển học bạ 2025",
          content:
            "Thông báo về việc xét tuyển đại học chính quy bằng phương thức xét học bạ THPT năm 2025...",
          score: 0.91,
        },
      ],
    };
  }

  const words = responseData.text.split(" ");
  let currentIndex = 0;

  const interval = setInterval(() => {
    if (controller.signal.aborted) {
      clearInterval(interval);
      return;
    }

    if (currentIndex < words.length) {
      const chunkSize = Math.floor(Math.random() * 3) + 1;
      const chunk = words.slice(currentIndex, currentIndex + chunkSize).join(" ");
      callbacks.onContent(
        (currentIndex === 0 ? "" : " ") + chunk
      );
      currentIndex += chunkSize;
    } else {
      clearInterval(interval);
      callbacks.onSources(responseData.sources);
      setTimeout(() => callbacks.onDone(), 200);
    }
  }, 30 + Math.random() * 50);

  return controller;
}
