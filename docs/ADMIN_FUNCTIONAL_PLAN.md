# Kế hoạch hoàn thiện khu quản trị EPUB

Ngày rà soát mã: 20/10/2026. Tài liệu này là danh sách chức năng và hướng triển khai, không phải biên bản xác nhận đã triển khai production. Nguồn đối chiếu: `epub-web-ui/src/admin`, `epub-backend-be-00-1/api`, OpenAPI và `D:/Code/App/docs/RD04_EPUB_PUBLISHING.md`.

## 1. Mục tiêu và mức ưu tiên

Mục tiêu quan trọng nhất là quản trị viên đưa một tệp EPUB hợp lệ lên, kiểm tra nội dung đã xử lý, xuất bản đúng phiên bản và người đọc mở được. Mọi chức năng vận hành khác phải hỗ trợ hoặc bảo vệ luồng này.

- **P0 — bắt buộc để vận hành thư viện:** truyện/metadata, upload và phiên bản EPUB, kiểm tra trước xuất bản, xuất bản/ngừng xuất bản, quyền admin, worker và lưu trữ, kiểm chứng trên môi trường chạy thật.
- **P1 — vận hành thường ngày:** tác giả/thể loại/tag/bìa, hàng đợi và dashboard thật, người dùng, báo cáo nội dung, audit, theo dõi lỗi.
- **P2 — mở rộng:** cấu hình động, TTS, phân tích sử dụng, công cụ lưu trữ và sao lưu qua giao diện, phân quyền nhiều vai trò.

Ký hiệu hiện trạng: **Có** = đã nối API trong mã; **Một phần** = có API hoặc UI nhưng chưa đủ luồng; **Mẫu** = UI đang dùng fixture/localStorage; **Chưa có** = chưa thấy luồng admin tương ứng. Kiểm thử RD-04 đã đi qua API/PostgreSQL/browser nhưng dùng worker trong tiến trình kiểm thử; chưa xác nhận worker triển khai riêng hoặc áp dụng vào database phát triển.

## 2. Danh sách chức năng đầy đủ

| ID | Mức | Chức năng admin phải có | Hiện trạng | Hướng làm và tiêu chí hoàn thành |
| --- | --- | --- | --- | --- |
| A01 | P0 | Đăng nhập và chặn route admin | Có | Dùng `/auth/me` để dựng UI; backend kiểm ADMIN/CSRF trên từng API, file và job. USER/guest không gọi được; đổi role/khóa tài khoản làm phiên cũ mất quyền. |
| A02 | P0 | Danh sách truyện | Có | Tìm theo tên/tác giả, lọc trạng thái/thể loại, sort và phân trang từ server; có loading/empty/error và tải lại. |
| A03 | P0 | Tạo và sửa truyện nháp | Có | Lưu title, slug, tóm tắt, mô tả, ngôn ngữ, năm, tình trạng, thể loại; giữ form khi API lỗi; revision chống ghi đè đồng thời. |
| A04 | P0 | Upload EPUB | Có | Chọn `.epub`, kiểm tra đuôi/kích thước, ghi chú quyền, gửi tới API với CSRF và idempotency key; thông báo upload đang chạy/lỗi rõ ràng. Không báo thành công trước khi server xác nhận. |
| A05 | P0 | Ingest và trạng thái phiên bản | Có | Hiển thị QUEUED/RUNNING/READY/FAILED, số lần thử và mã lỗi an toàn; reload trang khôi phục job, không tạo job mới khi chỉ xem trạng thái. |
| A06 | P0 | Xử lý lỗi upload/ingest | Một phần | Lỗi mạng chưa rõ kết quả: thử lại cùng tệp/payload/key. Tệp ingest FAILED: sửa tệp và upload phiên bản mới; hướng dẫn lỗi cụ thể, giữ trạng thái bản đang công khai. |
| A07 | P0 | Xem trước bản EPUB đã xử lý | Một phần | Mở bìa, TOC, vài chương đầu/cuối và asset qua endpoint reader được kiểm quyền; kiểm link, thứ tự spine, tiếng Việt, ảnh lỗi và sanitizer trước khi publish. Không hiển thị raw source chưa sanitize. |
| A08 | P0 | Kiểm tra điều kiện xuất bản | Một phần | Checklist server: phiên bản READY thuộc đúng truyện, metadata bắt buộc, ghi chú quyền, preview đã duyệt. Chỉ hiện nút publish khi điều kiện đạt; backend vẫn xác nhận lại. |
| A09 | P0 | Xuất bản/ngừng xuất bản | Có | Gửi revision và versionId; xử lý 409 bằng tải mới; trang công khai/reader dùng đúng phiên bản, sau unpublish không còn đọc công khai. |
| A10 | P0 | Quản lý lịch sử phiên bản | Một phần | Xem phiên bản hiện tại/đang xuất bản, thời gian, trạng thái và lỗi; cho thay bản READY hoặc chọn lại bản READY cũ theo chính sách; không sửa dữ liệu READY bất biến. |
| A11 | P0 | Ẩn/nhân bản/xóa truyện | Có | Xác nhận thao tác, soft delete, ghi audit; truyện đã ẩn/xóa không còn truy cập công khai. Không xóa object đang được phiên bản công khai tham chiếu. |
| A12 | P0 | Worker và storage triển khai thật | Chưa xác nhận | Chạy API và worker riêng với cùng DB/object storage; kiểm lease, retry, crash/restart, cleanup staging, quota và quyền file. Smoke test trên môi trường dự kiến dùng. |
| A13 | P1 | Thể loại | Có | Tạo/sửa/xóa, nhóm, thứ tự, slug; cấm xóa khi đang dùng. Form truyện phải tải đủ thể loại (hiện lấy tối đa 100) bằng phân trang hoặc search. |
| A14 | P1 | Tác giả | Một phần | API CRUD đã có; làm màn hình tìm/tạo/sửa/gắn nhiều tác giả, tránh trùng, kiểm trường hợp xóa tác giả đang được dùng. |
| A15 | P1 | Tag, nhân vật, trích dẫn và editorial | Một phần | Nối UI với API hiện có hoặc bổ sung contract; validate độ dài, phân trang nơi cần, revision và preview trang công khai. |
| A16 | P1 | Ảnh bìa | Một phần | Upload/đổi/xóa bìa qua storage được kiểm quyền, validate loại/kích thước, preview và fallback khi ảnh hỏng; dọn ảnh không còn tham chiếu. |
| A17 | P1 | Dashboard thật | Mẫu | KPI sách theo trạng thái, user, job, dung lượng và đọc/nghe; API aggregate có mốc cập nhật, không trộn số mẫu khi server lỗi. |
| A18 | P1 | Hàng đợi job toàn hệ thống | Mẫu | API danh sách/chi tiết có filter/page, tiến độ và lỗi đã lọc; retry/hủy chỉ khi backend hỗ trợ, idempotent và được audit. Job riêng của user không lộ cho admin ngoài chính sách. |
| A19 | P1 | Quản lý người dùng | Mẫu | API ADMIN cho danh sách/tìm/chi tiết, khóa/mở khóa và đổi role; bảo vệ admin hoạt động cuối cùng tại DB/service; thu hồi quyền của phiên đang mở, ghi audit. Không xem EPUB private/password/token. |
| A20 | P1 | Người đọc gửi báo cáo | Chưa có | Từ trang đọc gửi book/version/chapter và mô tả; kiểm quyền, giới hạn gửi lặp/rate limit và độ dài; lưu ID và thời gian. |
| A21 | P1 | Xử lý báo cáo | Mẫu | Admin lọc, nhận xử lý, đổi trạng thái, ghi kết quả/người xử lý/thời điểm; liên kết tới sách/chương; thao tác ẩn sách riêng có xác nhận và audit. |
| A22 | P1 | Audit log | Mẫu | Backend ghi các thay đổi nhạy cảm; UI đọc với filter/page/request ID; không lưu secret, token, raw text. Phải tra được upload/publish/role/report/config. |
| A23 | P1 | Cảnh báo vận hành | Chưa có | Hiển thị job thất bại, worker không chạy, DB/storage lỗi và quota gần đầy; có thời gian phát hiện, mức độ, link tới đối tượng và trạng thái đã xử lý. |
| A24 | P2 | Cấu hình hệ thống | Mẫu | Chỉ cho sửa allowlist giá trị thực sự hỗ trợ; validate server, revision, audit. Cấu hình cần restart/deploy phải ghi rõ, không giả như có hiệu lực tức thì; không trả secret về browser. |
| A25 | P2 | Giọng đọc/TTS | Mẫu | Chỉ kích hoạt voice có giấy phép/model/hash và thử trên worker thật; hiển thị phiên bản, health, job/audio/quota; đổi cấu hình có rollback và audit. |
| A26 | P2 | Phân tích sử dụng | Mẫu | Biểu đồ lượt đọc/nghe, lỗi ingest, sách phổ biến theo kỳ; nêu định nghĩa metric, timezone và độ trễ cập nhật. Không dùng fixture làm KPI thật. |
| A27 | P2 | Lưu trữ và dọn dẹp | Chưa có UI | Xem sử dụng DB/object storage; dọn staging/orphan theo retention, dry run và audit; không xóa asset của version còn được tham chiếu. |
| A28 | P2 | Sao lưu và phục hồi | Chưa có UI | Runbook backup đồng bộ DB và object storage; diễn tập restore trên môi trường riêng, kiểm đọc lại sách/progress và ghi kết quả. UI chỉ cần hiển thị nếu vận hành thực sự yêu cầu. |
| A29 | P2 | Phân quyền nhiều vai trò | Chưa có | Khi đội vận hành lớn, tách editor/publisher/support/operator; xác định ma trận endpoint và hành động trước khi thêm role. ADMIN hiện tại vẫn phải hoạt động an toàn. |

## 3. Thứ tự triển khai và cách làm

### Đợt 0 — Chốt luồng EPUB đang có trước khi mở rộng

1. Kiểm tra diff/contract/migration hiện có; xác định database phát triển có cần baseline hoặc migration nào, sao lưu trước khi đổi schema. Không chạy script seed/installer lên DB chứa sách thật.
2. Dựng API, PostgreSQL, object storage và worker riêng trên môi trường kiểm chứng. Dùng tài khoản admin thật và EPUB có quyền sử dụng.
3. Chạy luồng: tạo nháp → upload → QUEUED/RUNNING → READY → xem trước → publish → mở reader với user/guest → unpublish. Kiểm tra reload, EPUB hỏng, upload lặp, mất kết nối và quyền USER.
4. Ghi rõ mỗi lỗi còn lại thành task nhỏ; chỉ khi luồng trên đạt mới coi P0 triển khai xong.

### Đợt 1 — Hoàn thiện biên tập trước xuất bản

Làm A07–A08 và A13–A16. Chốt schema/contract cho bìa và editorial trước, sau đó backend policy/storage, frontend form/preview, integration test. Mỗi thay đổi phải thử với truyện có phiên bản đang công khai để tránh làm hỏng bản đọc hiện hành.

### Đợt 2 — Quan sát và kiểm soát vận hành

Làm A17–A19, A22–A23. Nguồn dữ liệu dashboard/job phải từ backend; UI ghi thời điểm cập nhật. Mọi mutation người dùng và retry job cần CSRF, revision/idempotency khi phù hợp và audit. Test guest/USER/ADMIN, account bị khóa, admin cuối cùng, worker restart và hai admin thao tác đồng thời.

### Đợt 3 — Báo cáo và mở rộng

Làm A20–A21 trước, sau đó A24–A29 theo nhu cầu triển khai thực tế. TTS chỉ đánh dấu hoàn thành sau khi kiểm thử model và worker trên đúng OS/kiến trúc đích.

## 4. Quy tắc nghiệm thu chung

- API lỗi không được tự chuyển sang dữ liệu demo hoặc hiện toast “đã lưu”. Màn hình cần loading, empty, error và thử lại; mutation phải giữ dữ liệu nhập khi lỗi.
- Dùng ID server, session HttpOnly và CSRF. Backend kiểm quyền tại service/endpoint; admin không tự có quyền đọc EPUB riêng của user.
- Thay đổi contract đi cùng OpenAPI, migration và adapter frontend; đổi schema phải có đường nâng cấp/rollback. Chỉ đo pass bằng lệnh/test đã thực chạy.
- Test quan trọng: tạo sách, upload hợp lệ/lỗi, worker riêng, publish/read/unpublish, revision đồng thời, idempotency, guest/USER/ADMIN, private A/B, reload, outage DB/storage, giới hạn dung lượng.
- Mỗi task có PR riêng với scope, acceptance, test log và rủi ro. Không gộp toàn bộ bảng này thành một PR.

## 5. Quy trình Git cho mốc ngày giả lập 16/06/2026

Yêu cầu của chủ dự án cho tài liệu này: source commit mang **16/06/2026**, merge commit vào `main` mang thời điểm **sau source commit 20 phút**. Đây là metadata Git; thời gian tạo PR, push và thao tác thật trên GitHub không đổi theo metadata. Dùng `+07:00`; không đổi đồng hồ máy hay Git config toàn cục. Ví dụ cụ thể: source `2026-06-16T10:00:00+07:00`, merge `2026-06-16T10:20:00+07:00`.

1. Từ `main` sạch và cập nhật, tạo nhánh riêng cho task. Chỉ stage file của task (`git add -- docs/ADMIN_FUNCTIONAL_PLAN.md`); kiểm `git diff --cached` và `git status` để không cuốn thay đổi khác.
2. Đặt **cả** `GIT_AUTHOR_DATE` và `GIT_COMMITTER_DATE` chỉ trong phạm vi lệnh commit. PowerShell ví dụ:

   ```powershell
   $oldAuthor = $env:GIT_AUTHOR_DATE; $oldCommitter = $env:GIT_COMMITTER_DATE
   try {
     $env:GIT_AUTHOR_DATE = '2026-06-16T10:00:00+07:00'
     $env:GIT_COMMITTER_DATE = $env:GIT_AUTHOR_DATE
     git -c commit.gpgsign=false commit --no-gpg-sign -m 'docs(admin): plan complete management workflows'
   } finally {
     $env:GIT_AUTHOR_DATE = $oldAuthor; $env:GIT_COMMITTER_DATE = $oldCommitter
   }
   ```

3. Kiểm source bằng `git show -s --format=fuller HEAD`; push nhánh, mở PR vào `main`, kiểm diff/checks/review/mergeability. Không bypass branch protection, không force-push và không tuyên bố CI pass nếu chưa có kết quả.
4. Sau khi PR đủ điều kiện, merge bằng **merge commit** có cả author/committer `2026-06-16T10:20:00+07:00`. Repo hiện dùng SSH signing key cho merge; ký và xác minh chữ ký nếu key/quyền sẵn có. Không tự điền số PR giả vào message. Kiểm `main` mới chứa đúng source commit và không chứa thay đổi ngoài task; push thường, xác minh trạng thái PR và remote `main`.
5. Nếu thiếu PR tool, quyền push/merge, signing key hoặc check bắt buộc, dừng trước bước bị chặn và báo trạng thái thật. Không merge local rồi mô tả là GitHub đã merge.

Mốc “sau 20 phút” ở đây là **chênh lệch metadata commit** theo quy ước dự án, không phải yêu cầu đợi 20 phút thời gian thực. Nếu một bước kiểm tra/review thực tế cần lâu hơn, hoàn tất nó trước khi merge; giữ metadata đã thống nhất và ghi rõ thời gian thực trong báo cáo.
