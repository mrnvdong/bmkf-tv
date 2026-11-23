# BMKF TV — Ứng dụng Xem Phim & Thể Thao Trực Tiếp cho Android TV

Ứng dụng Android TV viết bằng **Kotlin + Jetpack Compose + Media3 ExoPlayer**, tối ưu mượt 60fps & tiết kiệm CPU/RAM cho TV Android (kể cả TV 4K cấu hình thấp chạy Android 9+), điều khiển hoàn toàn bằng Remote D-pad.

## Các nguồn phim & thể thao tích hợp

### Nguồn phim (6 nguồn)
1. **KKPhim** (`phimapi.com`): Phim mới cập nhật, phim lẻ, phim bộ, hoạt hình, TV Shows, lọc thể loại, tìm kiếm và phát trực tiếp HLS (`.m3u8`).
2. **VSMOV** (`vsmov.com/api`): Phim mới, Subteam, giải mã `streamvsmov.com` lấy `master.m3u8` + phụ đề rời `.vtt`, tự bóc lớp PNG giả dạng trên segment MPEG-TS (`TsDisguiseStrippingDataSource`).
3. **CoBePhim** (`cobephim.wiki`): Phim Hot, Top tuần, bộ sưu tập Song ngữ / Trung Quốc / Âu Mỹ, đa server và giải mã link phát từng tập.
4. **K20 Stremio Addon** (`sc.k-20.xyz/manifest.json`): Tải động catalog chuẩn Stremio, phụ đề `.vtt` và truyền đủ `proxyHeaders`.
5. **ShowBox 4K / 1080P** (Stremio Addon + Cinemeta + OpenSubtitles v3 Tiếng Việt): Tách riêng server **ShowBox 4K** và **ShowBox 1080P**, tích hợp thêm cho các phim có IMDb ID từ KKPhim/VSMOV/CoBePhim. *(Cấu hình URL manifest cá nhân qua `local.properties` hoặc trực tiếp trong màn hình Cài đặt của app.)*
6. **KhoPhim** (`khophim.top` & Publisher API `kp.streamhub.solutions/publisher`): Chạy kép 2 chế độ — dùng ngay không cần key qua Web công khai, hoặc nhập API Key (`pk_live_...`) trong Cài đặt để dùng Publisher REST API chính thức.

### Nguồn Sport TV (6 nguồn thể thao trực tiếp)
- **Gà Vàng TV** (`gavang69.com`) — Bóng đá & Bóng rổ NBA BLV Việt (Tencent/Alibaba CDN HLS + HTTP-FLV).
- **Xôi Chè TV** (`xoiche.live`) — REST API bóng đá trực tiếp BLV Việt.
- **Cola TV** (`colatv111.live`) — Bóng đá & bóng rổ đa CDN BLV Việt + luồng HD gốc.
- **Xôi Lạc TV** (`xoilacz.ca`) — Bóng đá & đa môn thể thao BLV Việt (tự động cập nhật tên miền redirect).
- **HighFly Sports** — Luồng thể thao FHD trực tiếp *(tùy chọn cấu hình URL manifest cá nhân)*.
- **Nuvio Sports** — Trực tiếp bóng đá, MMA, kênh thể thao 24/7 & Replays.

## Tính năng nổi bật cho Android TV
- **Tự động chống lag Sport TV (`StreamSpeedTester`)**: Đo tốc độ thực tế song song từ mạng TV, tự chọn server mượt nhất, kèm watchdog tự động chuyển server khi đứng hình/buffering liên tục.
- **Tự động căn tỉ lệ màn hình TV (`VideoAspectController`)**: Khắc phục lỗi phim bị co/méo tỉ lệ anamorphic, hỗ trợ chuyển nhanh các chế độ (`Tự động`, `Vừa màn hình`, `Zoom`, `Đầy màn hình`, `16:9`).
- **Chống tím/xanh màu Dolby Vision (`VideoColorGuard`)**: Tự phát hiện TV không có bộ giải mã Dolby Vision phần cứng để tránh luồng DV Profile 5 gây tím hình, kèm nút chuyển nhanh giải mã `HW / SW`.
- **Tối ưu CPU & RAM cho TV**: Bản Release R8 nhẹ (~3.7 MB), giải mã ảnh `RGB_565` đúng kích thước thẻ, giới hạn bộ đệm video 24 MB, không recomposition khi ẩn thanh điều khiển player.
- **Trình phát Media3 ExoPlayer + WebView tối ưu Remote TV**:
  - `OK / Enter`: Phát / Tạm dừng hoặc mở thanh điều khiển.
  - `Trái / Phải`: Tua nhanh ±10 giây.
  - `Lên / Xuống`: Mở menu chọn tập, đổi Server/Nguồn, phụ đề, tốc độ (`0.5x – 2.0x`), tỉ lệ màn hình, Fix màu HW/SW.
  - `CH+ / CH-` (trong Sport TV): Chuyển nhanh giữa các trận đấu/kênh đang phát.

## Cấu hình tùy chọn & Build

Sao chép file mẫu `local.properties.example` thành `local.properties` (file này được `.gitignore` để không lộ token cá nhân):

```properties
sdk.dir=/path/to/Android/sdk
# Tùy chọn — hoặc có thể nhập trực tiếp trong màn hình Cài đặt trên TV:
# bmkf.showbox.manifestUrl=https://showbox.codiv.dpdns.org/<your-config>/manifest.json
# bmkf.highfly.manifestUrl=https://sports.highfly.dev/<your-token>/manifest.json
# bmkf.khophim.apiKey=pk_live_your_key_here
```

Build & cài đặt lên Android TV:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk

# Chạy toàn bộ unit test
./gradlew testDebugUnitTest

# Build bản Release tối ưu R8 (khuyên dùng cho Android TV)
./gradlew assembleRelease

# Cài đặt lên Android TV qua ADB
adb connect <IP_ANDROID_TV>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```
